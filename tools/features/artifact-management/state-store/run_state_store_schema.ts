import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import type {
  RunStateDatabase,
  RunStateStoreFailure,
  RunStateStoreFailureCode,
} from "./model/run_state_store.model";

export const CURRENT_SCHEMA_VERSION = 6;
const BUSY_TIMEOUT_MS = 5_000;

type Migration = {
  version: number;
  name: string;
  resourceFile: string;
};

const MIGRATIONS: readonly Migration[] = [
  {
    version: 1,
    name: "initial_run_state_store",
    resourceFile: "001_initial_run_state_store.sql",
  },
  {
    version: 2,
    name: "regression_suite_checkpoints",
    resourceFile: "002_regression_suite_checkpoints.sql",
  },
  {
    version: 3,
    name: "correlation_aggregate_observations",
    resourceFile: "003_correlation_aggregate_observations.sql",
  },
  {
    version: 4,
    name: "watcher_checkpoint_progress",
    resourceFile: "004_watcher_checkpoint_progress.sql",
  },
  {
    version: 5,
    name: "external_verification_summaries",
    resourceFile: "005_external_verification_summaries.sql",
  },
  {
    version: 6,
    name: "legacy_backfill_provenance",
    resourceFile: "006_legacy_backfill_provenance.sql",
  },
];

function readMigrationSql(resourceFile: string): string {
  return readFileSync(path.join(__dirname, "migrations", resourceFile), "utf8");
}

function runtimeChecksum(sql: string): string {
  return createHash("sha256").update(sql, "utf8").digest("hex");
}

function failure(
  reasonCode: RunStateStoreFailureCode,
  reason: string,
  nextAction: RunStateStoreFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateStoreFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function classify(error: unknown, databasePathAbs: string): RunStateStoreFailure {
  const message = (error instanceof Error ? error.message : String(error)).toLowerCase();
  if (message.includes("locked") || message.includes("busy"))
    return failure("state_store_locked", "run-state SQLite store is locked", "retry_state_store", {
      databasePathAbs,
    });
  if (
    message.includes("not a database") ||
    message.includes("malformed") ||
    message.includes("corrupt")
  )
    return failure(
      "state_store_corrupt",
      "run-state SQLite store could not be opened and was preserved for recovery",
      "rebuild_state_store",
      { databasePathAbs },
    );
  return failure(
    "state_store_migration_failed",
    "run-state SQLite migration failed",
    "rebuild_state_store",
    { databasePathAbs },
  );
}

export function applyRunStateStoreMigrations(
  database: RunStateDatabase,
  projectName: string,
  databasePathAbs: string,
): RunStateStoreFailure | { ok: true; schemaVersion: number } {
  try {
    database.exec("PRAGMA foreign_keys = ON;");
    database.exec(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS};`);
    database.exec("PRAGMA journal_mode = WAL;");
    database.exec("BEGIN IMMEDIATE;");
    try {
      const hasMigrationTable = database
        .prepare(
          "SELECT 1 AS present FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
        )
        .get();
      const hasResourceTable = database
        .prepare(
          "SELECT 1 AS present FROM sqlite_master WHERE type = 'table' AND name = 'schema_migration_resources'",
        )
        .get();
      if (hasMigrationTable && !hasResourceTable) {
        database.exec("ROLLBACK;");
        return failure(
          "state_store_migration_failed",
          "run-state SQLite migration resource metadata is missing",
          "rebuild_state_store",
          { databasePathAbs },
        );
      }
      database.exec(`
        CREATE TABLE IF NOT EXISTS schema_migrations (
          version INTEGER PRIMARY KEY,
          applied_at_epoch_ms INTEGER NOT NULL,
          migration_name TEXT NOT NULL,
          checksum TEXT NOT NULL
        );
      `);
      database.exec(
        `CREATE TABLE IF NOT EXISTS schema_migration_resources (
          version INTEGER PRIMARY KEY,
          resource_file TEXT NOT NULL,
          resource_checksum TEXT NOT NULL,
          observed_at_epoch_ms INTEGER NOT NULL
        );`,
      );
      const applied = database
        .prepare("SELECT version FROM schema_migrations ORDER BY version ASC")
        .all();
      const resourceChecksums = new Map<number, { resourceFile: string; checksum: string }>();
      for (const migration of MIGRATIONS) {
        const checksum = runtimeChecksum(readMigrationSql(migration.resourceFile));
        resourceChecksums.set(migration.version, {
          resourceFile: migration.resourceFile,
          checksum,
        });
        const storedResource = database
          .prepare(
            "SELECT resource_file, resource_checksum FROM schema_migration_resources WHERE version = ?",
          )
          .get(migration.version);
        if (
          storedResource &&
          (storedResource.resource_file !== migration.resourceFile ||
            storedResource.resource_checksum !== checksum)
        ) {
          database.exec("ROLLBACK;");
          return failure(
            "state_store_migration_failed",
            "run-state SQLite migration resource checksum does not match the persisted resource",
            "rebuild_state_store",
            {
              version: migration.version,
              resourceFile: migration.resourceFile,
              expectedChecksum: storedResource.resource_checksum,
              actualChecksum: checksum,
            },
          );
        }
      }
      const currentVersion =
        typeof applied.at(-1)?.version === "number" ? (applied.at(-1)?.version as number) : 0;
      if (currentVersion > CURRENT_SCHEMA_VERSION) {
        database.exec("ROLLBACK;");
        return failure(
          "state_store_schema_unsupported",
          "run-state SQLite store uses a newer schema version",
          "rebuild_state_store",
          { currentVersion, supportedVersion: CURRENT_SCHEMA_VERSION },
        );
      }
      for (const migration of MIGRATIONS) {
        const resource = resourceChecksums.get(migration.version);
        if (!resource) throw new Error("migration_resource_checksum_missing");
        if (migration.version > currentVersion) {
          database.exec(readMigrationSql(migration.resourceFile));
          database
            .prepare(
              "INSERT INTO schema_migrations (version, applied_at_epoch_ms, migration_name, checksum) VALUES (?, ?, ?, ?)",
            )
            .run(migration.version, Date.now(), migration.name, resource.checksum);
        }
        database
          .prepare(
            `INSERT INTO schema_migration_resources (
              version,
              resource_file,
              resource_checksum,
              observed_at_epoch_ms
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT(version) DO UPDATE SET
              resource_file = excluded.resource_file,
              resource_checksum = excluded.resource_checksum,
              observed_at_epoch_ms = excluded.observed_at_epoch_ms`,
          )
          .run(migration.version, resource.resourceFile, resource.checksum, Date.now());
      }
      const stored = database
        .prepare("SELECT metadata_value FROM store_metadata WHERE metadata_key = 'project_name'")
        .get();
      if (typeof stored?.metadata_value === "string" && stored.metadata_value !== projectName) {
        database.exec("ROLLBACK;");
        return failure(
          "state_store_project_mismatch",
          "run-state SQLite store belongs to a different project",
          "correct_state_store_input",
          { expectedProjectName: projectName, storedProjectName: stored.metadata_value },
        );
      }
      database
        .prepare(
          `INSERT INTO store_metadata (
            metadata_key,
            metadata_value,
            updated_at_epoch_ms
          )
          VALUES ('project_name', ?, ?)
          ON CONFLICT(metadata_key) DO UPDATE SET
            metadata_value = excluded.metadata_value,
            updated_at_epoch_ms = excluded.updated_at_epoch_ms`,
        )
        .run(projectName, Date.now());
      database.exec("COMMIT;");
      return { ok: true, schemaVersion: CURRENT_SCHEMA_VERSION };
    } catch (error) {
      try {
        database.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      throw error;
    }
  } catch (error) {
    return classify(error, databasePathAbs);
  }
}
