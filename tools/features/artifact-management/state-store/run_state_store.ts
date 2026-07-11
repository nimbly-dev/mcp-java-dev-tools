import { promises as fs } from "node:fs";
import path from "node:path";

const { DatabaseSync } = require("node:sqlite") as {
  DatabaseSync: new (location: string) => RunStateDatabase;
};

const CURRENT_SCHEMA_VERSION = 1;
const BUSY_TIMEOUT_MS = 5_000;

type RunStateDatabase = {
  exec(sql: string): void;
  prepare(sql: string): {
    get(...parameters: unknown[]): Record<string, unknown> | undefined;
    all(...parameters: unknown[]): Array<Record<string, unknown>>;
    run(...parameters: unknown[]): void;
  };
  close(): void;
};

export type RunStateStoreFailureCode =
  | "state_store_open_failed"
  | "state_store_locked"
  | "state_store_corrupt"
  | "state_store_schema_unsupported"
  | "state_store_migration_failed"
  | "state_store_project_mismatch"
  | "state_store_path_invalid";

export type RunStateStoreFailure = {
  ok: false;
  reasonCode: RunStateStoreFailureCode;
  reason: string;
  nextAction: "rebuild_state_store" | "retry_state_store" | "correct_state_store_input";
  reasonMeta?: Record<string, unknown>;
};

export type OpenRunStateStore = {
  ok: true;
  projectName: string;
  databasePathAbs: string;
  schemaVersion: number;
  database: RunStateDatabase;
  close(): void;
};

export type RunStateStoreOpenResult = OpenRunStateStore | RunStateStoreFailure;

export type RunStateArtifactLink = {
  artifactKind: "context_resolved" | "execution_result" | "evidence" | "correlation" | "execution_orchestration";
  pathRel: string;
  createdAtEpochMs: number;
  planName?: string;
  runId?: string;
  suiteRunId?: string;
  checksum?: string;
};

const MIGRATIONS = [
  {
    version: 1,
    name: "initial_run_state_store",
    checksum: "sha256:7ed008f226aef9fd6cf37d2b9a3ab1fcf117a6a78d35e8584d1f9c4fd4d96931",
    sql: `
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        applied_at_epoch_ms INTEGER NOT NULL,
        migration_name TEXT NOT NULL,
        checksum TEXT NOT NULL
      );
      CREATE TABLE store_metadata (
        metadata_key TEXT PRIMARY KEY,
        metadata_value TEXT NOT NULL,
        updated_at_epoch_ms INTEGER NOT NULL
      );
      CREATE TABLE artifacts (
        artifact_id INTEGER PRIMARY KEY,
        project_name TEXT NOT NULL,
        plan_name TEXT,
        run_id TEXT,
        suite_run_id TEXT,
        artifact_kind TEXT NOT NULL,
        path_rel TEXT NOT NULL,
        checksum TEXT,
        created_at_epoch_ms INTEGER NOT NULL,
        UNIQUE(project_name, artifact_kind, path_rel)
      );
    `,
  },
] as const;

function failure(
  reasonCode: RunStateStoreFailureCode,
  reason: string,
  nextAction: RunStateStoreFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateStoreFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function normalizeProjectName(projectName: string): string | RunStateStoreFailure {
  const normalized = projectName.trim();
  if (!normalized || normalized === "." || normalized === ".." || /[\\/]/.test(normalized)) {
    return failure("state_store_path_invalid", "projectName must be a non-empty path segment", "correct_state_store_input");
  }
  return normalized;
}

function isSafeRelativePath(pathRel: string): boolean {
  return pathRel.length > 0 && !path.isAbsolute(pathRel) && !path.win32.isAbsolute(pathRel) && !pathRel.split(/[\\/]/).includes("..");
}

function classifyDatabaseError(error: unknown, databasePathAbs: string, fallback: RunStateStoreFailureCode): RunStateStoreFailure {
  const message = error instanceof Error ? error.message : String(error);
  const normalized = message.toLowerCase();
  if (normalized.includes("database is locked") || normalized.includes("busy")) {
    return failure("state_store_locked", "run-state SQLite store is locked", "retry_state_store", { databasePathAbs });
  }
  if (normalized.includes("not a database") || normalized.includes("malformed") || normalized.includes("corrupt")) {
    return failure("state_store_corrupt", "run-state SQLite store could not be opened and was preserved for recovery", "rebuild_state_store", { databasePathAbs });
  }
  return failure(fallback, "run-state SQLite store operation failed", fallback === "state_store_migration_failed" ? "rebuild_state_store" : "retry_state_store", { databasePathAbs });
}

function applyMigrations(database: RunStateDatabase, projectName: string, databasePathAbs: string): RunStateStoreFailure | { ok: true; schemaVersion: number } {
  try {
    database.exec("PRAGMA foreign_keys = ON;");
    database.exec(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS};`);
    database.exec("PRAGMA journal_mode = WAL;");
    database.exec("BEGIN IMMEDIATE;");
    try {
      database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at_epoch_ms INTEGER NOT NULL, migration_name TEXT NOT NULL, checksum TEXT NOT NULL);");
      const appliedMigrations = database.prepare("SELECT version, checksum FROM schema_migrations ORDER BY version ASC").all();
      for (const applied of appliedMigrations) {
        const expected = MIGRATIONS.find((migration) => migration.version === applied.version);
        if (!expected) continue;
        if (applied.checksum !== expected.checksum) {
          database.exec("ROLLBACK;");
          return failure("state_store_migration_failed", "run-state SQLite migration checksum does not match the supported schema", "rebuild_state_store", {
            version: applied.version,
            expectedChecksum: expected.checksum,
            actualChecksum: applied.checksum,
          });
        }
      }
      const latest = appliedMigrations.at(-1);
      const currentVersion = typeof latest?.version === "number" ? latest.version : 0;
      if (currentVersion > CURRENT_SCHEMA_VERSION) {
        database.exec("ROLLBACK;");
        return failure("state_store_schema_unsupported", "run-state SQLite store uses a newer schema version", "rebuild_state_store", {
          currentVersion,
          supportedVersion: CURRENT_SCHEMA_VERSION,
        });
      }
      for (const migration of MIGRATIONS) {
        if (migration.version <= currentVersion) continue;
        database.exec(migration.sql);
        database.prepare("INSERT INTO schema_migrations (version, applied_at_epoch_ms, migration_name, checksum) VALUES (?, ?, ?, ?)").run(
          migration.version,
          Date.now(),
          migration.name,
          migration.checksum,
        );
      }
      const storedProject = database.prepare("SELECT metadata_value FROM store_metadata WHERE metadata_key = 'project_name'").get();
      if (typeof storedProject?.metadata_value === "string" && storedProject.metadata_value !== projectName) {
        database.exec("ROLLBACK;");
        return failure("state_store_project_mismatch", "run-state SQLite store belongs to a different project", "correct_state_store_input", {
          expectedProjectName: projectName,
          storedProjectName: storedProject.metadata_value,
        });
      }
      database.prepare("INSERT INTO store_metadata (metadata_key, metadata_value, updated_at_epoch_ms) VALUES ('project_name', ?, ?) ON CONFLICT(metadata_key) DO UPDATE SET metadata_value = excluded.metadata_value, updated_at_epoch_ms = excluded.updated_at_epoch_ms").run(projectName, Date.now());
      database.exec("COMMIT;");
      return { ok: true, schemaVersion: CURRENT_SCHEMA_VERSION };
    } catch (error) {
      try { database.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      throw error;
    }
  } catch (error) {
    return classifyDatabaseError(error, databasePathAbs, "state_store_migration_failed");
  }
}

/** Opens the per-project operational state store. Callers must always invoke close(). */
export async function openRunStateStore(args: {
  workspaceRootAbs: string;
  projectName: string;
}): Promise<RunStateStoreOpenResult> {
  const projectName = normalizeProjectName(args.projectName);
  if (typeof projectName !== "string") return projectName;
  const rootAbs = path.resolve(args.workspaceRootAbs, ".mcpjvm");
  const projectDirAbs = path.resolve(rootAbs, projectName);
  if (!projectDirAbs.startsWith(`${rootAbs}${path.sep}`)) {
    return failure("state_store_path_invalid", "projectName resolves outside the .mcpjvm directory", "correct_state_store_input");
  }
  try {
    await fs.mkdir(projectDirAbs, { recursive: true });
  } catch (error) {
    return failure("state_store_open_failed", "run-state SQLite directory could not be created", "retry_state_store", {
      projectDirAbs,
      error: error instanceof Error ? error.message : String(error),
    });
  }
  const databasePathAbs = path.join(projectDirAbs, "run-state.sqlite");
  let database: RunStateDatabase;
  try {
    database = new DatabaseSync(databasePathAbs);
  } catch (error) {
    return classifyDatabaseError(error, databasePathAbs, "state_store_open_failed");
  }
  const migration = applyMigrations(database, projectName, databasePathAbs);
  if (!migration.ok) {
    database.close();
    return migration;
  }
  return {
    ok: true,
    projectName,
    databasePathAbs,
    schemaVersion: migration.schemaVersion,
    database,
    close: () => database.close(),
  };
}

/** Records portable canonical Artifact linkage; runtime behavior remains owned by Regression Suite. */
export function upsertRunStateArtifact(store: OpenRunStateStore, artifact: RunStateArtifactLink): RunStateStoreFailure | { ok: true } {
  if (!isSafeRelativePath(artifact.pathRel)) {
    return failure("state_store_path_invalid", "Artifact path must be workspace-relative and may not traverse parents", "correct_state_store_input", {
      pathRel: artifact.pathRel,
    });
  }
  try {
    store.database.prepare(`INSERT INTO artifacts (project_name, plan_name, run_id, suite_run_id, artifact_kind, path_rel, checksum, created_at_epoch_ms)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(project_name, artifact_kind, path_rel) DO UPDATE SET
        plan_name = excluded.plan_name, run_id = excluded.run_id, suite_run_id = excluded.suite_run_id,
        checksum = excluded.checksum, created_at_epoch_ms = excluded.created_at_epoch_ms`).run(
      store.projectName, artifact.planName ?? null, artifact.runId ?? null, artifact.suiteRunId ?? null,
      artifact.artifactKind, artifact.pathRel.replaceAll("\\", "/"), artifact.checksum ?? null, artifact.createdAtEpochMs,
    );
    return { ok: true };
  } catch (error) {
    return failure("state_store_open_failed", "run-state Artifact linkage could not be persisted", "retry_state_store", {
      error: error instanceof Error ? error.message : String(error),
    });
  }
}
