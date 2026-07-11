import { promises as fs } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";

const { DatabaseSync } = require("node:sqlite") as {
  DatabaseSync: new (location: string) => RunStateDatabase;
};

const CURRENT_SCHEMA_VERSION = 3;
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

export type RegressionSuiteCheckpoint = {
  suiteRunId: string;
  executionProfile: string;
  status: "pass" | "fail" | "blocked" | "partial_fail" | "in_progress";
  startedAtEpochMs: number;
  updatedAtEpochMs: number;
  nextPlanOrder?: number;
  activePlanName?: string;
  activePlanOrder?: number;
  activeRunId?: string;
  activePhase?: "trigger" | "watchers" | "external_verification";
  continuation?: Record<string, unknown>;
  completedAtEpochMs?: number;
  reasonCode?: string;
  expectedRevision?: number;
  ownerId?: string;
  leaseExpiresAtEpochMs?: number;
};

export type RegressionPlanRunProjection = {
  planName: string;
  runId: string;
  status: "executed" | "blocked" | "skipped";
  runDirPathRel: string;
  planOrder?: number;
  runStatus?: "pass" | "fail" | "blocked" | "in_progress";
  stepCount?: number;
  failedStepCount?: number;
  startedAtEpochMs?: number;
  completedAtEpochMs?: number;
  reasonCode?: string;
};

export type RunStateCheckpointFailure = {
  ok: false;
  reasonCode:
    | "suite_checkpoint_conflict"
    | "suite_checkpoint_stale_revision"
    | "suite_checkpoint_owner_active"
    | "suite_checkpoint_lease_expired"
    | "suite_checkpoint_invalid"
    | "suite_state_transition_invalid"
    | "run_state_persist_failed";
  reason: string;
  nextAction: "resume_same_suite" | "retry_state_store" | "correct_checkpoint_input";
  reasonMeta?: Record<string, unknown>;
};

export type PersistRegressionSuiteStateResult =
  | { ok: true; revision: number }
  | RunStateCheckpointFailure
  | RunStateStoreFailure;

export type AcquireRegressionSuiteLeaseResult =
  | { ok: true; revision: number; leaseExpiresAtEpochMs: number }
  | RunStateCheckpointFailure;

export type CorrelationObservation = {
  strictLineKey: string;
  sequenceOrder: number;
  selectorPolicy: "exact_instance" | "any_instance" | "all_instances" | "aggregate" | "quorum";
  operator: "exact" | "at_least" | "at_most" | "range";
  expectedHitDelta?: number;
  expectedMinHitDelta?: number;
  expectedMaxHitDelta?: number;
  probeId: string;
  runtimeInstanceId: string;
  baselineHitCount: number;
  currentHitCount: number;
  observedAtEpochMs: number;
  /** Rejects a stale caller before it can overwrite a newer aggregate sample. */
  expectedRevision?: number;
};

export type CorrelationObservationResult =
  | { ok: true; revision: number; observedHitDelta: number; status: "collecting" | "matched" | "fail_closed" }
  | CorrelationPersistenceFailure;

export type CorrelationPersistenceFailure = {
  ok: false;
  reasonCode:
    | "correlation_identity_invalid"
    | "correlation_revision_conflict"
    | "correlation_runtime_instance_changed"
    | "correlation_hit_count_non_monotonic"
    | "correlation_expectation_exceeded"
    | "correlation_persist_failed";
  reason: string;
  nextAction: "correct_correlation_input" | "resume_same_suite" | "retry_state_store";
  reasonMeta?: Record<string, unknown>;
};

export type CorrelationSession = {
  planName: string;
  runId: string;
  correlationSessionId: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  maxWindowMs: number;
  startedAtEpochMs: number;
  status: "collecting" | "correlated" | "fail_closed";
  reasonCode: string;
  correlationPathRel?: string;
  expectations?: Array<{
    strictLineKey: string;
    sequenceOrder: number;
    selectorPolicy: CorrelationObservation["selectorPolicy"];
    operator: CorrelationObservation["operator"];
    expectedHitDelta?: number;
    expectedMinHitDelta?: number;
    expectedMaxHitDelta?: number;
    label?: string;
  }>;
};

export type CorrelationSessionResult = { ok: true; revision: number } | CorrelationPersistenceFailure;

export type PersistedRegressionSuiteCheckpoint = {
  suiteRunId: string;
  executionProfile: string;
  status: RegressionSuiteCheckpoint["status"];
  revision: number;
  startedAtEpochMs: number;
  updatedAtEpochMs: number;
  nextPlanOrder?: number;
  activePlanName?: string;
  activePlanOrder?: number;
  activeRunId?: string;
  activePhase?: RegressionSuiteCheckpoint["activePhase"];
  continuation?: Record<string, unknown>;
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
      CREATE TABLE correlation_runs (correlation_run_pk INTEGER PRIMARY KEY, project_name TEXT NOT NULL, plan_name TEXT NOT NULL, run_id TEXT NOT NULL, suite_run_id TEXT, correlation_session_id TEXT NOT NULL, status TEXT NOT NULL, reason_code TEXT NOT NULL, expected_line_count INTEGER NOT NULL, matched_line_count INTEGER NOT NULL, window_start_epoch_ms INTEGER, window_end_epoch_ms INTEGER, max_window_ms INTEGER NOT NULL, started_at_epoch_ms INTEGER NOT NULL, correlated_at_epoch_ms INTEGER, revision INTEGER NOT NULL DEFAULT 0, correlation_path_rel TEXT, UNIQUE(project_name, run_id, correlation_session_id));
      CREATE TABLE correlation_keys (correlation_key_pk INTEGER PRIMARY KEY, correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk), key_type TEXT NOT NULL, key_value_sanitized TEXT, key_value_hash TEXT, UNIQUE(correlation_run_pk, key_type, key_value_hash));
      CREATE TABLE correlation_line_expectations (line_expectation_pk INTEGER PRIMARY KEY, correlation_run_pk INTEGER NOT NULL REFERENCES correlation_runs(correlation_run_pk), sequence_order INTEGER NOT NULL, label TEXT, strict_line_key TEXT NOT NULL, selector_policy TEXT NOT NULL, operator TEXT NOT NULL, expected_hit_delta INTEGER, expected_min_hit_delta INTEGER, expected_max_hit_delta INTEGER, status TEXT NOT NULL, reason_code TEXT, first_hit_epoch_ms INTEGER, last_hit_epoch_ms INTEGER, UNIQUE(correlation_run_pk, sequence_order, strict_line_key));
      CREATE TABLE correlation_probe_observations (probe_observation_pk INTEGER PRIMARY KEY, line_expectation_pk INTEGER NOT NULL REFERENCES correlation_line_expectations(line_expectation_pk), probe_id TEXT NOT NULL, logical_service_id TEXT, service_instance_id TEXT, runtime_instance_id TEXT NOT NULL, probe_address_observed TEXT, observed_scope_state TEXT, scope_state_observed_at_epoch_ms INTEGER, scope_state_expires_at_epoch_ms INTEGER, baseline_hit_count INTEGER NOT NULL, current_hit_count INTEGER NOT NULL, observed_hit_delta INTEGER NOT NULL, last_hit_epoch_ms INTEGER, first_observed_at_epoch_ms INTEGER NOT NULL, last_observed_at_epoch_ms INTEGER NOT NULL, sample_count INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, UNIQUE(line_expectation_pk, probe_id, runtime_instance_id));
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

function checkpointFailure(
  reasonCode: RunStateCheckpointFailure["reasonCode"],
  reason: string,
  nextAction: RunStateCheckpointFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateCheckpointFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function isTerminalSuiteStatus(status: string): boolean {
  return status === "pass" || status === "fail" || status === "blocked" || status === "partial_fail";
}

/**
 * Persists a Regression Suite checkpoint after canonical Artifacts were written.
 * The caller supplies expectedRevision when resuming an existing suite to prevent
 * concurrent/stale calls from advancing the same suiteRunId.
 */
export function persistRegressionSuiteState(args: {
  store: OpenRunStateStore;
  checkpoint: RegressionSuiteCheckpoint;
  planRuns: RegressionPlanRunProjection[];
}): PersistRegressionSuiteStateResult {
  const checkpoint = args.checkpoint;
  if (!checkpoint.suiteRunId.trim() || !checkpoint.executionProfile.trim() || !Number.isInteger(checkpoint.startedAtEpochMs) || !Number.isInteger(checkpoint.updatedAtEpochMs)) {
    return checkpointFailure("suite_checkpoint_invalid", "suite checkpoint identity and timestamps are required", "correct_checkpoint_input");
  }
  if (checkpoint.continuation && JSON.stringify(checkpoint.continuation).length > 16_384) {
    return checkpointFailure("suite_checkpoint_invalid", "suite checkpoint continuation exceeds the bounded size", "correct_checkpoint_input");
  }
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db.prepare("SELECT suite_run_pk, status, revision, owner_id, lease_expires_at_epoch_ms FROM suite_runs WHERE project_name = ? AND suite_run_id = ?").get(args.store.projectName, checkpoint.suiteRunId);
      const currentRevision = typeof existing?.revision === "number" ? existing.revision : 0;
      if (typeof checkpoint.expectedRevision === "number" && existing && checkpoint.expectedRevision !== currentRevision) {
        db.exec("ROLLBACK;");
        return checkpointFailure("suite_checkpoint_stale_revision", "suite checkpoint revision is stale", "resume_same_suite", { expectedRevision: checkpoint.expectedRevision, currentRevision, suiteRunId: checkpoint.suiteRunId });
      }
      if (existing && typeof existing.status === "string" && isTerminalSuiteStatus(existing.status) && existing.status !== checkpoint.status) {
        db.exec("ROLLBACK;");
        return checkpointFailure("suite_state_transition_invalid", "a terminal suite checkpoint cannot be advanced", "resume_same_suite", { suiteRunId: checkpoint.suiteRunId, status: existing.status });
      }
      const now = checkpoint.updatedAtEpochMs;
      const nextRevision = currentRevision + 1;
      db.prepare(`INSERT INTO suite_runs (project_name, suite_run_id, execution_profile, status, next_plan_order, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json, owner_id, lease_expires_at_epoch_ms, revision, started_at_epoch_ms, updated_at_epoch_ms, completed_at_epoch_ms, reason_code)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(project_name, suite_run_id) DO UPDATE SET
          execution_profile = excluded.execution_profile, status = excluded.status, next_plan_order = excluded.next_plan_order,
          active_plan_name = excluded.active_plan_name, active_plan_order = excluded.active_plan_order, active_run_id = excluded.active_run_id,
          active_phase = excluded.active_phase, continuation_json = excluded.continuation_json, owner_id = excluded.owner_id,
          lease_expires_at_epoch_ms = excluded.lease_expires_at_epoch_ms, revision = excluded.revision,
          updated_at_epoch_ms = excluded.updated_at_epoch_ms, completed_at_epoch_ms = excluded.completed_at_epoch_ms, reason_code = excluded.reason_code`).run(
        args.store.projectName, checkpoint.suiteRunId.trim(), checkpoint.executionProfile.trim(), checkpoint.status,
        checkpoint.nextPlanOrder ?? null, checkpoint.activePlanName ?? null, checkpoint.activePlanOrder ?? null, checkpoint.activeRunId ?? null,
        checkpoint.activePhase ?? null, checkpoint.continuation ? JSON.stringify(checkpoint.continuation) : null,
        checkpoint.ownerId ?? null, checkpoint.leaseExpiresAtEpochMs ?? null, nextRevision, checkpoint.startedAtEpochMs, now,
        checkpoint.completedAtEpochMs ?? null, checkpoint.reasonCode ?? null,
      );
      const suiteRow = db.prepare("SELECT suite_run_pk FROM suite_runs WHERE project_name = ? AND suite_run_id = ?").get(args.store.projectName, checkpoint.suiteRunId);
      const suiteRunPk = suiteRow?.suite_run_pk;
      if (typeof suiteRunPk !== "number") throw new Error("suite_checkpoint_missing_after_upsert");
      for (const planRun of args.planRuns) {
        if (!planRun.planName.trim() || !planRun.runId.trim() || !isSafeRelativePath(planRun.runDirPathRel)) {
          db.exec("ROLLBACK;");
          return checkpointFailure("suite_checkpoint_invalid", "plan-run identity and workspace-relative Artifact path are required", "correct_checkpoint_input");
        }
        db.prepare(`INSERT INTO plan_runs (suite_run_pk, project_name, plan_name, run_id, plan_order, status, step_count, failed_step_count, started_at_epoch_ms, completed_at_epoch_ms, revision, reason_code, run_dir_path_rel)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
          ON CONFLICT(project_name, plan_name, run_id) DO UPDATE SET
            suite_run_pk = excluded.suite_run_pk, plan_order = excluded.plan_order, status = excluded.status, step_count = excluded.step_count,
            failed_step_count = excluded.failed_step_count, started_at_epoch_ms = excluded.started_at_epoch_ms,
            completed_at_epoch_ms = excluded.completed_at_epoch_ms, revision = plan_runs.revision + 1, reason_code = excluded.reason_code,
            run_dir_path_rel = excluded.run_dir_path_rel`).run(
          suiteRunPk, args.store.projectName, planRun.planName.trim(), planRun.runId.trim(), planRun.planOrder ?? null, planRun.status,
          planRun.stepCount ?? null, planRun.failedStepCount ?? null, planRun.startedAtEpochMs ?? null, planRun.completedAtEpochMs ?? null,
          planRun.reasonCode ?? null, planRun.runDirPathRel.replaceAll("\\", "/"),
        );
      }
      db.exec("COMMIT;");
      return { ok: true, revision: nextRevision };
    } catch (error) {
      try { db.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      throw error;
    }
  } catch (error) {
    return checkpointFailure("run_state_persist_failed", "suite checkpoint could not be persisted", "retry_state_store", {
      error: error instanceof Error ? error.message : String(error),
      suiteRunId: checkpoint.suiteRunId,
    });
  }
}

/** Reads only bounded operational checkpoint fields; canonical Artifacts remain execution evidence. */
export function readRegressionSuiteCheckpoint(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
}): PersistedRegressionSuiteCheckpoint | null {
  const row = args.store.database.prepare(`SELECT suite_run_id, execution_profile, status, revision, started_at_epoch_ms, updated_at_epoch_ms,
    next_plan_order, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json
    FROM suite_runs WHERE project_name = ? AND suite_run_id = ?`).get(args.store.projectName, args.suiteRunId);
  if (!row || typeof row.suite_run_id !== "string" || typeof row.execution_profile !== "string" || typeof row.status !== "string" || typeof row.revision !== "number" || typeof row.started_at_epoch_ms !== "number" || typeof row.updated_at_epoch_ms !== "number") return null;
  let continuation: Record<string, unknown> | undefined;
  if (typeof row.continuation_json === "string") {
    try {
      const parsed = JSON.parse(row.continuation_json) as unknown;
      if (typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)) continuation = parsed as Record<string, unknown>;
    } catch { return null; }
  }
  return {
    suiteRunId: row.suite_run_id,
    executionProfile: row.execution_profile,
    status: row.status as RegressionSuiteCheckpoint["status"],
    revision: row.revision,
    startedAtEpochMs: row.started_at_epoch_ms,
    updatedAtEpochMs: row.updated_at_epoch_ms,
    ...(typeof row.next_plan_order === "number" ? { nextPlanOrder: row.next_plan_order } : {}),
    ...(typeof row.active_plan_name === "string" ? { activePlanName: row.active_plan_name } : {}),
    ...(typeof row.active_plan_order === "number" ? { activePlanOrder: row.active_plan_order } : {}),
    ...(typeof row.active_run_id === "string" ? { activeRunId: row.active_run_id } : {}),
    ...(row.active_phase === "trigger" || row.active_phase === "watchers" || row.active_phase === "external_verification" ? { activePhase: row.active_phase } : {}),
    ...(continuation ? { continuation } : {}),
  };
}

/** Acquires or renews the bounded owner lease before a resumed suite can advance. */
export function acquireRegressionSuiteLease(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
  ownerId: string;
  nowEpochMs: number;
  leaseDurationMs: number;
}): AcquireRegressionSuiteLeaseResult {
  if (!args.suiteRunId.trim() || !args.ownerId.trim() || !Number.isInteger(args.nowEpochMs) || !Number.isInteger(args.leaseDurationMs) || args.leaseDurationMs <= 0) {
    return checkpointFailure("suite_checkpoint_invalid", "suite lease identity and bounded duration are required", "correct_checkpoint_input");
  }
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db.prepare("SELECT owner_id, lease_expires_at_epoch_ms, revision FROM suite_runs WHERE project_name = ? AND suite_run_id = ?").get(args.store.projectName, args.suiteRunId);
      if (!existing || typeof existing.revision !== "number") {
        db.exec("ROLLBACK;");
        return checkpointFailure("suite_checkpoint_invalid", "suite checkpoint must exist before lease acquisition", "resume_same_suite");
      }
      const ownerId = typeof existing.owner_id === "string" ? existing.owner_id : undefined;
      const expiry = typeof existing.lease_expires_at_epoch_ms === "number" ? existing.lease_expires_at_epoch_ms : undefined;
      if (ownerId && ownerId !== args.ownerId && typeof expiry === "number" && expiry > args.nowEpochMs) {
        db.exec("ROLLBACK;");
        return checkpointFailure("suite_checkpoint_owner_active", "another caller currently owns the suite checkpoint", "resume_same_suite", { suiteRunId: args.suiteRunId, leaseExpiresAtEpochMs: expiry });
      }
      const nextRevision = existing.revision + 1;
      const leaseExpiresAtEpochMs = args.nowEpochMs + args.leaseDurationMs;
      db.prepare("UPDATE suite_runs SET owner_id = ?, lease_expires_at_epoch_ms = ?, revision = ?, updated_at_epoch_ms = ? WHERE project_name = ? AND suite_run_id = ?").run(args.ownerId, leaseExpiresAtEpochMs, nextRevision, args.nowEpochMs, args.store.projectName, args.suiteRunId);
      db.exec("COMMIT;");
      return { ok: true, revision: nextRevision, leaseExpiresAtEpochMs };
    } catch (error) {
      try { db.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      throw error;
    }
  } catch (error) {
    return checkpointFailure("run_state_persist_failed", "suite lease could not be persisted", "retry_state_store", { error: error instanceof Error ? error.message : String(error) });
  }
}

export function releaseRegressionSuiteLease(args: { store: OpenRunStateStore; suiteRunId: string; ownerId: string; nowEpochMs: number }): void {
  args.store.database.prepare("UPDATE suite_runs SET owner_id = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = ? WHERE project_name = ? AND suite_run_id = ? AND owner_id = ?").run(args.nowEpochMs, args.store.projectName, args.suiteRunId, args.ownerId);
}

/** Persists bounded correlation identity and a deterministic hashed key projection. */
export function persistCorrelationSession(args: {
  store: OpenRunStateStore;
  projectName: string;
  session: CorrelationSession;
}): CorrelationSessionResult {
  const session = args.session;
  if (
    args.projectName !== args.store.projectName || !session.planName.trim() || !session.runId.trim()
    || !session.correlationSessionId.trim() || !Number.isInteger(session.maxWindowMs) || session.maxWindowMs <= 0
    || !Number.isInteger(session.startedAtEpochMs) || !session.reasonCode.trim()
    || (session.correlationPathRel !== undefined && !isSafeRelativePath(session.correlationPathRel))
    || (session.keyValue !== undefined && session.keyValue.length > 512)
  ) return correlationFailure("correlation_identity_invalid", "correlation session identity, bounded window, and portable Artifact path are required", "correct_correlation_input");
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db.prepare("SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?").get(args.projectName, session.runId, session.correlationSessionId);
      if (existing && typeof existing.correlation_run_pk === "number") {
        db.prepare("UPDATE correlation_runs SET status = ?, reason_code = ?, max_window_ms = ?, correlation_path_rel = ?, revision = revision + 1 WHERE correlation_run_pk = ?").run(session.status, session.reasonCode, session.maxWindowMs, session.correlationPathRel ?? null, existing.correlation_run_pk);
      } else {
        db.prepare("INSERT INTO correlation_runs (project_name, plan_name, run_id, correlation_session_id, status, reason_code, expected_line_count, matched_line_count, max_window_ms, started_at_epoch_ms, correlation_path_rel) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)").run(args.projectName, session.planName, session.runId, session.correlationSessionId, session.status, session.reasonCode, session.maxWindowMs, session.startedAtEpochMs, session.correlationPathRel ?? null);
      }
      const run = db.prepare("SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?").get(args.projectName, session.runId, session.correlationSessionId);
      if (typeof run?.correlation_run_pk !== "number" || typeof run.revision !== "number") throw new Error("correlation_run_missing");
      for (const expectation of session.expectations ?? []) {
        db.prepare("INSERT INTO correlation_line_expectations (correlation_run_pk, sequence_order, label, strict_line_key, selector_policy, operator, expected_hit_delta, expected_min_hit_delta, expected_max_hit_delta, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'collecting') ON CONFLICT(correlation_run_pk, sequence_order, strict_line_key) DO NOTHING").run(run.correlation_run_pk, expectation.sequenceOrder, expectation.label ?? null, expectation.strictLineKey, expectation.selectorPolicy, expectation.operator, expectation.expectedHitDelta ?? null, expectation.expectedMinHitDelta ?? null, expectation.expectedMaxHitDelta ?? null);
      }
      if (session.keyValue) {
        const keyHash = createHash("sha256").update(session.keyValue).digest("hex");
        db.prepare("INSERT INTO correlation_keys (correlation_run_pk, key_type, key_value_sanitized, key_value_hash) VALUES (?, ?, NULL, ?) ON CONFLICT(correlation_run_pk, key_type, key_value_hash) DO NOTHING").run(run.correlation_run_pk, session.keyType, keyHash);
      }
      db.exec("COMMIT;");
      return { ok: true, revision: run.revision };
    } catch (error) {
      try { db.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      throw error;
    }
  } catch (error) {
    return correlationFailure("correlation_persist_failed", "correlation session could not be persisted", "retry_state_store", { error: error instanceof Error ? error.message : String(error) });
  }
}

function correlationFailure(
  reasonCode: CorrelationPersistenceFailure["reasonCode"],
  reason: string,
  nextAction: CorrelationPersistenceFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): CorrelationPersistenceFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function evaluateCorrelationObservation(observation: CorrelationObservation, observedHitDelta: number): "collecting" | "matched" | "fail_closed" {
  if (observation.operator === "exact") return observedHitDelta === observation.expectedHitDelta ? "matched" : "collecting";
  if (observation.operator === "at_least") return observedHitDelta >= (observation.expectedHitDelta ?? 0) ? "matched" : "collecting";
  if (observation.operator === "at_most") return observedHitDelta <= (observation.expectedHitDelta ?? 0) ? "matched" : "collecting";
  return observedHitDelta >= (observation.expectedMinHitDelta ?? 0) && observedHitDelta <= (observation.expectedMaxHitDelta ?? 0) ? "matched" : "collecting";
}

function isValidCorrelationExpectation(observation: CorrelationObservation): boolean {
  if (observation.operator === "range") {
    const min = observation.expectedMinHitDelta;
    const max = observation.expectedMaxHitDelta;
    return Number.isInteger(min) && Number.isInteger(max) && min !== undefined && max !== undefined
      && min >= 0 && max >= min;
  }
  const expected = observation.expectedHitDelta;
  return Number.isInteger(expected) && expected !== undefined && expected >= 0;
}

/** Writes one bounded aggregate observation; callers poll counters rather than writing per Line Hit. */
export function upsertCorrelationObservation(args: {
  store: OpenRunStateStore;
  projectName: string;
  planName: string;
  runId: string;
  correlationSessionId: string;
  maxWindowMs: number;
  observation: CorrelationObservation;
}): CorrelationObservationResult {
  const o = args.observation;
  if (
    args.projectName !== args.store.projectName || !args.planName.trim() || !args.runId.trim()
    || !args.correlationSessionId.trim() || !Number.isInteger(args.maxWindowMs) || args.maxWindowMs <= 0
    || !/^[\w.$]+#[\w$<>]+:\d+$/.test(o.strictLineKey) || !o.probeId.trim() || !o.runtimeInstanceId.trim()
    || !Number.isInteger(o.sequenceOrder) || o.sequenceOrder < 1 || !Number.isInteger(o.baselineHitCount)
    || !Number.isInteger(o.currentHitCount) || o.currentHitCount < o.baselineHitCount
    || !Number.isInteger(o.observedAtEpochMs) || !isValidCorrelationExpectation(o)
  ) return correlationFailure("correlation_identity_invalid", "correlation identity, Strict Line Key, and bounded expectation are required", "correct_correlation_input");

  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existingRun = db.prepare("SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?").get(args.projectName, args.runId, args.correlationSessionId);
      if (typeof o.expectedRevision === "number" && typeof existingRun?.revision === "number" && o.expectedRevision !== existingRun.revision) {
        db.exec("ROLLBACK;");
        return correlationFailure("correlation_revision_conflict", "correlation revision is stale", "resume_same_suite", { expectedRevision: o.expectedRevision, currentRevision: existingRun.revision });
      }
      if (existingRun && typeof existingRun.correlation_run_pk === "number") {
        db.prepare("UPDATE correlation_runs SET revision = revision + 1 WHERE correlation_run_pk = ?").run(existingRun.correlation_run_pk);
      } else {
        db.prepare("INSERT INTO correlation_runs (project_name, plan_name, run_id, correlation_session_id, status, reason_code, expected_line_count, matched_line_count, max_window_ms, started_at_epoch_ms) VALUES (?, ?, ?, ?, 'collecting', 'collecting', 0, 0, ?, ?)").run(args.projectName, args.planName, args.runId, args.correlationSessionId, args.maxWindowMs, o.observedAtEpochMs);
      }
      const run = db.prepare("SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?").get(args.projectName, args.runId, args.correlationSessionId);
      if (typeof run?.correlation_run_pk !== "number" || typeof run.revision !== "number") throw new Error("correlation_run_missing");
      const runPk = run.correlation_run_pk;
      db.prepare("INSERT INTO correlation_line_expectations (correlation_run_pk, sequence_order, strict_line_key, selector_policy, operator, expected_hit_delta, expected_min_hit_delta, expected_max_hit_delta, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'collecting') ON CONFLICT(correlation_run_pk, sequence_order, strict_line_key) DO NOTHING").run(runPk, o.sequenceOrder, o.strictLineKey, o.selectorPolicy, o.operator, o.expectedHitDelta ?? null, o.expectedMinHitDelta ?? null, o.expectedMaxHitDelta ?? null);
      const line = db.prepare("SELECT line_expectation_pk FROM correlation_line_expectations WHERE correlation_run_pk = ? AND sequence_order = ? AND strict_line_key = ?").get(runPk, o.sequenceOrder, o.strictLineKey);
      if (typeof line?.line_expectation_pk !== "number") throw new Error("correlation_line_missing");
      const runtime = db.prepare("SELECT runtime_instance_id FROM correlation_probe_observations WHERE line_expectation_pk = ? AND probe_id = ? LIMIT 1").get(line.line_expectation_pk, o.probeId);
      if (typeof runtime?.runtime_instance_id === "string" && runtime.runtime_instance_id !== o.runtimeInstanceId) {
        db.exec("ROLLBACK;");
        return correlationFailure("correlation_runtime_instance_changed", "Probe runtime instance changed during an active observation", "resume_same_suite", { probeId: o.probeId, priorRuntimeInstanceId: runtime.runtime_instance_id, runtimeInstanceId: o.runtimeInstanceId });
      }
      const prior = db.prepare("SELECT baseline_hit_count, current_hit_count, revision FROM correlation_probe_observations WHERE line_expectation_pk = ? AND probe_id = ? AND runtime_instance_id = ?").get(line.line_expectation_pk, o.probeId, o.runtimeInstanceId);
      if (typeof prior?.current_hit_count === "number" && o.currentHitCount < prior.current_hit_count) {
        db.exec("ROLLBACK;");
        return correlationFailure("correlation_hit_count_non_monotonic", "correlation hit count decreased within one runtime instance", "correct_correlation_input");
      }
      const baselineHitCount = typeof prior?.baseline_hit_count === "number" ? prior.baseline_hit_count : o.baselineHitCount;
      const observedHitDelta = o.currentHitCount - baselineHitCount;
      if ((o.operator === "exact" || o.operator === "at_most") && observedHitDelta > (o.expectedHitDelta ?? 0)) {
        db.exec("ROLLBACK;");
        return correlationFailure("correlation_expectation_exceeded", "correlation hit delta exceeds its bounded expectation", "correct_correlation_input", { observedHitDelta });
      }
      if (o.operator === "range" && observedHitDelta > (o.expectedMaxHitDelta ?? 0)) {
        db.exec("ROLLBACK;");
        return correlationFailure("correlation_expectation_exceeded", "correlation hit delta exceeds its bounded range", "correct_correlation_input", { observedHitDelta });
      }
      const status = evaluateCorrelationObservation(o, observedHitDelta);
      db.prepare("INSERT INTO correlation_probe_observations (line_expectation_pk, probe_id, runtime_instance_id, baseline_hit_count, current_hit_count, observed_hit_delta, first_observed_at_epoch_ms, last_observed_at_epoch_ms, sample_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1) ON CONFLICT(line_expectation_pk, probe_id, runtime_instance_id) DO UPDATE SET current_hit_count = excluded.current_hit_count, observed_hit_delta = excluded.observed_hit_delta, last_observed_at_epoch_ms = excluded.last_observed_at_epoch_ms, sample_count = correlation_probe_observations.sample_count + 1, revision = correlation_probe_observations.revision + 1").run(line.line_expectation_pk, o.probeId, o.runtimeInstanceId, baselineHitCount, o.currentHitCount, observedHitDelta, o.observedAtEpochMs, o.observedAtEpochMs);
      db.prepare("UPDATE correlation_line_expectations SET status = ?, reason_code = ?, last_hit_epoch_ms = ? WHERE line_expectation_pk = ?").run(status, status === "matched" ? "ok" : "collecting", o.observedAtEpochMs, line.line_expectation_pk);
      const summary = db.prepare("SELECT count(*) AS expected_line_count, sum(CASE WHEN status = 'matched' THEN 1 ELSE 0 END) AS matched_line_count FROM correlation_line_expectations WHERE correlation_run_pk = ?").get(runPk);
      const expectedLineCount = typeof summary?.expected_line_count === "number" ? summary.expected_line_count : 0;
      const matchedLineCount = typeof summary?.matched_line_count === "number" ? summary.matched_line_count : 0;
      db.prepare("UPDATE correlation_runs SET expected_line_count = ?, matched_line_count = ?, status = CASE WHEN status = 'fail_closed' THEN status ELSE ? END, reason_code = CASE WHEN status = 'fail_closed' THEN reason_code ELSE ? END, correlated_at_epoch_ms = CASE WHEN status <> 'fail_closed' AND ? = ? AND ? > 0 THEN ? ELSE correlated_at_epoch_ms END WHERE correlation_run_pk = ?").run(expectedLineCount, matchedLineCount, matchedLineCount === expectedLineCount ? "correlated" : "collecting", matchedLineCount === expectedLineCount ? "ok" : "collecting", matchedLineCount, expectedLineCount, expectedLineCount, o.observedAtEpochMs, runPk);
      db.exec("COMMIT;");
      return { ok: true, revision: run.revision, observedHitDelta, status };
    } catch (error) {
      try { db.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      throw error;
    }
  } catch (error) {
    return correlationFailure("correlation_persist_failed", "correlation observation could not be persisted", "retry_state_store", { error: error instanceof Error ? error.message : String(error) });
  }
}
