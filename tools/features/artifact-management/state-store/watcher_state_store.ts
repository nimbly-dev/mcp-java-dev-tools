import path from "node:path";
import type {
  OpenRunStateStore,
  WatcherPersistenceFailure,
  WatcherPersistenceResult,
  WatcherRunProjection,
} from "./model/run_state_store.model";

export type {
  WatcherRunProjection,
  WatcherPersistenceFailure,
  WatcherPersistenceResult,
} from "./model/run_state_store.model";

function isSafeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !path.isAbsolute(pathRel) &&
    !path.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}

function isTerminalWatcherStatus(status: string): boolean {
  return (
    status === "pass" ||
    status === "fail_assertion" ||
    status === "blocked_dependency" ||
    status === "blocked_runtime"
  );
}

function boundedWatcherJson(value: unknown): string | null {
  if (typeof value === "undefined") return null;
  const seen = new WeakSet<object>();
  const sanitized = JSON.parse(
    JSON.stringify(value, (key, entry: unknown) => {
      if (
        /authorization|credential|password|secret|token|cookie|rawbody|responsebody|(^|_)body($|_)/i.test(
          key,
        )
      )
        return "[REDACTED]";
      if (typeof entry === "object" && entry !== null) {
        if (seen.has(entry)) return "[CIRCULAR]";
        seen.add(entry);
      }
      return entry;
    }),
  ) as unknown;
  const serialized = JSON.stringify(sanitized);
  return serialized.length <= 8_192 ? serialized : JSON.stringify({ truncated: true });
}

/** Reads a persisted Watcher summary that was sanitized and size-bounded at write time. */
export function parseBoundedWatcherJson(value: string | null | undefined): unknown {
  if (!value || value.length > 8_192) return undefined;
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return undefined;
  }
}

/** Re-sanitizes a persisted Watcher summary before it crosses the MCP boundary. */
export function sanitizePersistedWatcherJson(
  value: string | null | undefined,
): { ok: true; value: unknown } | { ok: false } {
  if (!value || value.length > 8_192) return { ok: false };
  try {
    const parsed = JSON.parse(value) as unknown;
    const sanitized = boundedWatcherJson(parsed);
    if (!sanitized) return { ok: false };
    return { ok: true, value: JSON.parse(sanitized) as unknown };
  } catch {
    return { ok: false };
  }
}

function watcherFailure(
  reasonCode: WatcherPersistenceFailure["reasonCode"],
  reason: string,
  nextAction: WatcherPersistenceFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): WatcherPersistenceFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

/** Persists one bounded Watcher checkpoint and its sanitized attempt summaries. */
export function upsertWatcherRun(args: {
  store: OpenRunStateStore;
  projectName: string;
  projection: WatcherRunProjection;
}): WatcherPersistenceResult {
  const p = args.projection;
  const planName = p.planName.trim();
  const runId = p.runId.trim();
  const watcherName = p.watcherName.trim();
  const providerType = p.providerType.trim();
  if (
    args.projectName !== args.store.projectName ||
    !planName ||
    !runId ||
    !watcherName ||
    !Number.isInteger(p.dependencyStepOrder) ||
    p.dependencyStepOrder < 1 ||
    !Number.isInteger(p.watcherIndex) ||
    p.watcherIndex < 0 ||
    !providerType ||
    !Number.isInteger(p.startedAtEpochMs) ||
    !Number.isInteger(p.deadlineAtEpochMs) ||
    p.deadlineAtEpochMs < p.startedAtEpochMs ||
    !Number.isInteger(p.timeoutMs) ||
    p.timeoutMs <= 0 ||
    !Number.isInteger(p.pollIntervalMs) ||
    p.pollIntervalMs <= 0 ||
    !Number.isInteger(p.retryMax) ||
    p.retryMax <= 0 ||
    !Number.isInteger(p.attemptCount) ||
    p.attemptCount < 0 ||
    p.attemptCount > p.retryMax ||
    (p.nextAttemptAtEpochMs !== undefined &&
      (!Number.isInteger(p.nextAttemptAtEpochMs) || p.nextAttemptAtEpochMs < 0)) ||
    (p.status === "in_progress" && p.completedAtEpochMs !== undefined) ||
    (p.completedAtEpochMs !== undefined && p.completedAtEpochMs < p.startedAtEpochMs) ||
    (p.continuation !== undefined && typeof p.continuation !== "object")
  )
    return watcherFailure(
      "watcher_checkpoint_invalid",
      "Watcher identity, bounded policy, timestamps, and attempt count are invalid",
      "correct_watcher_input",
      {
        planName,
        runId,
        watcherName,
        dependencyStepOrder: p.dependencyStepOrder,
        watcherIndex: p.watcherIndex,
        providerType,
        status: p.status,
        startedAtEpochMs: p.startedAtEpochMs,
        deadlineAtEpochMs: p.deadlineAtEpochMs,
        timeoutMs: p.timeoutMs,
        pollIntervalMs: p.pollIntervalMs,
        retryMax: p.retryMax,
        attemptCount: p.attemptCount,
        nextAttemptAtEpochMs: p.nextAttemptAtEpochMs,
        completedAtEpochMs: p.completedAtEpochMs,
        continuationType: typeof p.continuation,
      },
    );
  if (p.artifactPathRel !== undefined && !isSafeRelativePath(p.artifactPathRel)) {
    return watcherFailure(
      "watcher_checkpoint_invalid",
      "Watcher Artifact path must be workspace-relative",
      "correct_watcher_input",
    );
  }
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      db.prepare(
        `INSERT INTO plan_runs (project_name, plan_name, run_id, status, run_dir_path_rel)
        VALUES (?, ?, ?, 'in_progress', ?)
        ON CONFLICT(project_name, plan_name, run_id) DO NOTHING`,
      ).run(
        args.projectName,
        planName,
        runId,
        p.artifactPathRel ??
          `.mcpjvm/${args.projectName}/plans/regression/${planName}/runs/${runId}`,
      );
      const plan = db
        .prepare(
          "SELECT plan_run_pk FROM plan_runs WHERE project_name = ? AND plan_name = ? AND run_id = ?",
        )
        .get(args.projectName, planName, runId);
      if (typeof plan?.plan_run_pk !== "number") throw new Error("watcher_plan_run_missing");
      const existing = db
        .prepare(
          "SELECT watcher_run_pk, suite_run_id, watcher_name, dependency_step_order, watcher_index, provider_type, status, revision, attempt_count, deadline_at_epoch_ms FROM watcher_runs WHERE project_name = ? AND plan_name = ? AND run_id = ? AND watcher_name = ?",
        )
        .get(args.projectName, planName, runId, watcherName);
      const currentRevision = typeof existing?.revision === "number" ? existing.revision : 0;
      if (typeof p.revision === "number" && existing && p.revision !== currentRevision) {
        db.exec("ROLLBACK;");
        return watcherFailure(
          "watcher_checkpoint_stale_revision",
          "Watcher checkpoint revision is stale",
          "resume_same_suite",
          { expectedRevision: p.revision, currentRevision },
        );
      }
      if (
        existing &&
        typeof existing.status === "string" &&
        isTerminalWatcherStatus(existing.status) &&
        existing.status !== p.status
      ) {
        db.exec("ROLLBACK;");
        return watcherFailure(
          "watcher_checkpoint_conflict",
          "Terminal Watcher state is immutable",
          "resume_same_suite",
          { status: existing.status },
        );
      }
      if (
        existing &&
        (String(existing.suite_run_id ?? "") !== String(p.suiteRunId ?? "") ||
          existing.watcher_name !== watcherName ||
          existing.dependency_step_order !== p.dependencyStepOrder ||
          existing.watcher_index !== p.watcherIndex ||
          existing.provider_type !== providerType)
      ) {
        db.exec("ROLLBACK;");
        return watcherFailure(
          "watcher_resume_identity_mismatch",
          "Watcher resume identity does not match the persisted checkpoint",
          "resume_same_suite",
          { planName, runId, watcherName },
        );
      }
      if (
        existing &&
        typeof existing.attempt_count === "number" &&
        p.attemptCount < existing.attempt_count
      ) {
        db.exec("ROLLBACK;");
        return watcherFailure(
          "watcher_attempt_non_monotonic",
          "Watcher attempt count cannot decrease",
          "resume_same_suite",
          { currentAttemptCount: existing.attempt_count, attemptCount: p.attemptCount },
        );
      }
      if (
        existing &&
        typeof existing.deadline_at_epoch_ms === "number" &&
        p.deadlineAtEpochMs !== existing.deadline_at_epoch_ms
      ) {
        db.exec("ROLLBACK;");
        return watcherFailure(
          "watcher_deadline_invalid",
          "Watcher deadline cannot change during resume",
          "resume_same_suite",
          {
            currentDeadlineAtEpochMs: existing.deadline_at_epoch_ms,
            deadlineAtEpochMs: p.deadlineAtEpochMs,
          },
        );
      }
      const nextRevision = currentRevision + 1;
      db.prepare(
        `INSERT INTO watcher_runs (plan_run_pk, project_name, plan_name, run_id, suite_run_id, watcher_name, dependency_step_order, watcher_index, provider_type, status, outcome, reason_code, started_at_epoch_ms, deadline_at_epoch_ms, completed_at_epoch_ms, timeout_ms, poll_interval_ms, retry_max, attempt_count, next_attempt_at_epoch_ms, last_observation_summary_json, last_assertion_summary_json, continuation_json, revision, artifact_path_rel)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(project_name, plan_name, run_id, watcher_name) DO UPDATE SET
          suite_run_id = excluded.suite_run_id, status = excluded.status, outcome = excluded.outcome, reason_code = excluded.reason_code,
          completed_at_epoch_ms = excluded.completed_at_epoch_ms, attempt_count = excluded.attempt_count, next_attempt_at_epoch_ms = excluded.next_attempt_at_epoch_ms,
          last_observation_summary_json = excluded.last_observation_summary_json, last_assertion_summary_json = excluded.last_assertion_summary_json,
          continuation_json = excluded.continuation_json, revision = excluded.revision, artifact_path_rel = excluded.artifact_path_rel`,
      ).run(
        plan.plan_run_pk,
        args.projectName,
        planName,
        runId,
        p.suiteRunId ?? null,
        watcherName,
        p.dependencyStepOrder,
        p.watcherIndex,
        providerType,
        p.status,
        p.outcome,
        p.reasonCode ?? null,
        p.startedAtEpochMs,
        p.deadlineAtEpochMs,
        p.completedAtEpochMs ?? null,
        p.timeoutMs,
        p.pollIntervalMs,
        p.retryMax,
        p.attemptCount,
        p.nextAttemptAtEpochMs ?? null,
        boundedWatcherJson(p.lastObservation),
        boundedWatcherJson(p.lastAssertion),
        boundedWatcherJson(p.continuation),
        nextRevision,
        p.artifactPathRel ?? null,
      );
      const watcher = db
        .prepare(
          "SELECT watcher_run_pk FROM watcher_runs WHERE project_name = ? AND plan_name = ? AND run_id = ? AND watcher_name = ?",
        )
        .get(args.projectName, planName, runId, watcherName);
      if (typeof watcher?.watcher_run_pk !== "number")
        throw new Error("watcher_run_missing_after_upsert");
      for (const attempt of (p.attempts ?? []).slice(-25)) {
        if (
          !Number.isInteger(attempt.attemptNumber) ||
          attempt.attemptNumber < 1 ||
          attempt.attemptNumber > p.attemptCount ||
          !Number.isInteger(attempt.observedAtEpochMs)
        ) {
          db.exec("ROLLBACK;");
          return watcherFailure(
            "watcher_checkpoint_invalid",
            "Watcher attempt history is invalid",
            "correct_watcher_input",
          );
        }
        db.prepare(
          `INSERT INTO watcher_attempts (watcher_run_pk, attempt_number, observed_at_epoch_ms, status, reason_code, duration_ms, observation_summary_json)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(watcher_run_pk, attempt_number) DO UPDATE SET observed_at_epoch_ms = excluded.observed_at_epoch_ms, status = excluded.status, reason_code = excluded.reason_code, duration_ms = excluded.duration_ms, observation_summary_json = excluded.observation_summary_json`,
        ).run(
          watcher.watcher_run_pk,
          attempt.attemptNumber,
          attempt.observedAtEpochMs,
          attempt.status,
          attempt.reasonCode ?? null,
          attempt.durationMs ?? null,
          boundedWatcherJson(attempt.observationSummary),
        );
      }
      db.exec("COMMIT;");
      return { ok: true, revision: nextRevision };
    } catch (error) {
      try {
        db.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      throw error;
    }
  } catch (error) {
    return watcherFailure(
      "watcher_persist_failed",
      "Watcher checkpoint could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}
