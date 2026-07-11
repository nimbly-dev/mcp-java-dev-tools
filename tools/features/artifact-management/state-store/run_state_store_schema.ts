import type {
  RunStateDatabase,
  RunStateStoreFailure,
  RunStateStoreFailureCode,
} from "./run_state_store.model";

export const CURRENT_SCHEMA_VERSION = 4;
const BUSY_TIMEOUT_MS = 5_000;

type Migration = { version: number; name: string; checksum: string; sql: string };

const MIGRATIONS: readonly Migration[] = [
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
  {
    version: 2,
    name: "regression_suite_checkpoints",
    checksum: "sha256:43cd9a83284fd48258975d0bb8c25828483e15911e3aab2df28583bf9e7c6804",
    sql: `
      CREATE TABLE suite_runs (
        suite_run_pk INTEGER PRIMARY KEY,
        project_name TEXT NOT NULL,
        suite_run_id TEXT NOT NULL,
        execution_profile TEXT,
        status TEXT NOT NULL,
        next_plan_order INTEGER,
        active_plan_name TEXT,
        active_plan_order INTEGER,
        active_run_id TEXT,
        active_phase TEXT CHECK(active_phase IN ('trigger', 'watchers', 'external_verification')),
        continuation_json TEXT,
        owner_id TEXT,
        lease_expires_at_epoch_ms INTEGER,
        revision INTEGER NOT NULL DEFAULT 0,
        started_at_epoch_ms INTEGER NOT NULL,
        updated_at_epoch_ms INTEGER NOT NULL,
        completed_at_epoch_ms INTEGER,
        reason_code TEXT,
        UNIQUE(project_name, suite_run_id)
      );

      CREATE TABLE plan_runs (
        plan_run_pk INTEGER PRIMARY KEY,
        suite_run_pk INTEGER REFERENCES suite_runs(suite_run_pk),
        project_name TEXT NOT NULL,
        plan_name TEXT NOT NULL,
        run_id TEXT NOT NULL,
        plan_order INTEGER,
        status TEXT NOT NULL,
        step_count INTEGER,
        failed_step_count INTEGER,
        started_at_epoch_ms INTEGER,
        completed_at_epoch_ms INTEGER,
        revision INTEGER NOT NULL DEFAULT 0,
        reason_code TEXT,
        run_dir_path_rel TEXT NOT NULL,
        UNIQUE(project_name, plan_name, run_id)
      );
    `,
  },
  {
    version: 3,
    name: "correlation_aggregate_observations",
    checksum: "sha256:4cc117d3a1afc66c6cb5f7d0385f6b5f9bf5ac4fcde979f7c0a22da6a7d9a372",
    sql: `
      CREATE TABLE correlation_runs (
        correlation_run_pk INTEGER PRIMARY KEY,
        project_name TEXT NOT NULL,
        plan_name TEXT NOT NULL,
        run_id TEXT NOT NULL,
        suite_run_id TEXT,
        correlation_session_id TEXT NOT NULL,
        status TEXT NOT NULL,
        reason_code TEXT NOT NULL,
        expected_line_count INTEGER NOT NULL,
        matched_line_count INTEGER NOT NULL,
        window_start_epoch_ms INTEGER,
        window_end_epoch_ms INTEGER,
        max_window_ms INTEGER NOT NULL,
        started_at_epoch_ms INTEGER NOT NULL,
        correlated_at_epoch_ms INTEGER,
        revision INTEGER NOT NULL DEFAULT 0,
        correlation_path_rel TEXT,
        UNIQUE(project_name, run_id, correlation_session_id)
      );

      CREATE TABLE correlation_keys (
        correlation_key_pk INTEGER PRIMARY KEY,
        correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk),
        key_type TEXT NOT NULL,
        key_value_sanitized TEXT,
        key_value_hash TEXT,
        UNIQUE(correlation_run_pk, key_type, key_value_hash)
      );

      CREATE TABLE correlation_line_expectations (
        line_expectation_pk INTEGER PRIMARY KEY,
        correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk),
        sequence_order INTEGER NOT NULL,
        label TEXT,
        strict_line_key TEXT NOT NULL,
        selector_policy TEXT NOT NULL,
        operator TEXT NOT NULL,
        expected_hit_delta INTEGER,
        expected_min_hit_delta INTEGER,
        expected_max_hit_delta INTEGER,
        status TEXT NOT NULL,
        reason_code TEXT,
        first_hit_epoch_ms INTEGER,
        last_hit_epoch_ms INTEGER,
        UNIQUE(correlation_run_pk, sequence_order, strict_line_key)
      );

      CREATE TABLE correlation_probe_observations (
        probe_observation_pk INTEGER PRIMARY KEY,
        line_expectation_pk INTEGER NOT NULL REFERENCES correlation_line_expectations(line_expectation_pk),
        probe_id TEXT NOT NULL,
        logical_service_id TEXT,
        service_instance_id TEXT,
        runtime_instance_id TEXT NOT NULL,
        probe_address_observed TEXT,
        observed_scope_state TEXT,
        scope_state_observed_at_epoch_ms INTEGER,
        scope_state_expires_at_epoch_ms INTEGER,
        baseline_hit_count INTEGER NOT NULL,
        current_hit_count INTEGER NOT NULL,
        observed_hit_delta INTEGER NOT NULL,
        last_hit_epoch_ms INTEGER,
        first_observed_at_epoch_ms INTEGER NOT NULL,
        last_observed_at_epoch_ms INTEGER NOT NULL,
        sample_count INTEGER NOT NULL,
        revision INTEGER NOT NULL DEFAULT 0,
        UNIQUE(line_expectation_pk, probe_id, runtime_instance_id)
      );
    `,
  },
  {
    version: 4,
    name: "watcher_checkpoint_progress",
    checksum: "sha256:watcher_checkpoint_progress_v1",
    sql: `
      CREATE TABLE watcher_runs (
        watcher_run_pk INTEGER PRIMARY KEY,
        plan_run_pk INTEGER NOT NULL REFERENCES plan_runs(plan_run_pk),
        project_name TEXT NOT NULL,
        plan_name TEXT NOT NULL,
        run_id TEXT NOT NULL,
        suite_run_id TEXT,
        watcher_name TEXT NOT NULL,
        dependency_step_order INTEGER NOT NULL,
        watcher_index INTEGER NOT NULL,
        provider_type TEXT NOT NULL,
        status TEXT NOT NULL,
        outcome TEXT NOT NULL,
        reason_code TEXT,
        started_at_epoch_ms INTEGER NOT NULL,
        deadline_at_epoch_ms INTEGER NOT NULL,
        completed_at_epoch_ms INTEGER,
        timeout_ms INTEGER NOT NULL,
        poll_interval_ms INTEGER NOT NULL,
        retry_max INTEGER NOT NULL,
        attempt_count INTEGER NOT NULL,
        next_attempt_at_epoch_ms INTEGER,
        last_observation_summary_json TEXT,
        last_assertion_summary_json TEXT,
        continuation_json TEXT,
        revision INTEGER NOT NULL DEFAULT 0,
        artifact_path_rel TEXT,
        UNIQUE(project_name, plan_name, run_id, watcher_name)
      );

      CREATE TABLE watcher_attempts (
        watcher_attempt_pk INTEGER PRIMARY KEY,
        watcher_run_pk INTEGER NOT NULL REFERENCES watcher_runs(watcher_run_pk),
        attempt_number INTEGER NOT NULL,
        observed_at_epoch_ms INTEGER NOT NULL,
        status TEXT NOT NULL,
        reason_code TEXT,
        duration_ms INTEGER,
        observation_summary_json TEXT,
        UNIQUE(watcher_run_pk, attempt_number)
      );
    `,
  },
];

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
      database.exec(
        "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at_epoch_ms INTEGER NOT NULL, migration_name TEXT NOT NULL, checksum TEXT NOT NULL);",
      );
      const applied = database
        .prepare("SELECT version, checksum FROM schema_migrations ORDER BY version ASC")
        .all();
      for (const row of applied) {
        const migration = MIGRATIONS.find((item) => item.version === row.version);
        if (migration && migration.checksum !== row.checksum) {
          database.exec("ROLLBACK;");
          return failure(
            "state_store_migration_failed",
            "run-state SQLite migration checksum does not match the supported schema",
            "rebuild_state_store",
            { version: row.version },
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
        if (migration.version <= currentVersion) continue;
        database.exec(migration.sql);
        database
          .prepare(
            "INSERT INTO schema_migrations (version, applied_at_epoch_ms, migration_name, checksum) VALUES (?, ?, ?, ?)",
          )
          .run(migration.version, Date.now(), migration.name, migration.checksum);
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
          "INSERT INTO store_metadata (metadata_key, metadata_value, updated_at_epoch_ms) VALUES ('project_name', ?, ?) ON CONFLICT(metadata_key) DO UPDATE SET metadata_value = excluded.metadata_value, updated_at_epoch_ms = excluded.updated_at_epoch_ms",
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
