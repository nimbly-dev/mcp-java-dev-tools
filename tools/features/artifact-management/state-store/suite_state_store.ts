import path from "node:path";
import type {
  OpenRunStateStore,
  PersistRegressionSuiteStateResult,
  RegressionPlanRunProjection,
  RegressionSuiteCheckpoint,
  RunStateCheckpointFailure,
  PersistedRegressionSuiteCheckpoint,
  PersistedRegressionSuiteState,
  AcquireRegressionSuiteLeaseResult,
} from "./model/run_state_store.model";

function checkpointFailure(
  reasonCode: RunStateCheckpointFailure["reasonCode"],
  reason: string,
  nextAction: RunStateCheckpointFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateCheckpointFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function isTerminalSuiteStatus(status: string): boolean {
  return (
    status === "pass" || status === "fail" || status === "blocked" || status === "partial_fail"
  );
}

export function persistRegressionSuiteState(args: {
  store: OpenRunStateStore;
  checkpoint: RegressionSuiteCheckpoint;
  planRuns: RegressionPlanRunProjection[];
}): PersistRegressionSuiteStateResult {
  const checkpoint = args.checkpoint;
  if (
    !checkpoint.suiteRunId.trim() ||
    !checkpoint.executionProfile.trim() ||
    !Number.isInteger(checkpoint.startedAtEpochMs) ||
    !Number.isInteger(checkpoint.updatedAtEpochMs)
  )
    return checkpointFailure(
      "suite_checkpoint_invalid",
      "suite checkpoint identity and timestamps are required",
      "correct_checkpoint_input",
    );
  if (checkpoint.continuation && JSON.stringify(checkpoint.continuation).length > 16_384)
    return checkpointFailure(
      "suite_checkpoint_invalid",
      "suite checkpoint continuation exceeds the bounded size",
      "correct_checkpoint_input",
    );
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db
        .prepare(
          "SELECT suite_run_pk, status, revision FROM suite_runs WHERE project_name = ? AND suite_run_id = ?",
        )
        .get(args.store.projectName, checkpoint.suiteRunId);
      const currentRevision = typeof existing?.revision === "number" ? existing.revision : 0;
      if (
        typeof checkpoint.expectedRevision === "number" &&
        existing &&
        checkpoint.expectedRevision !== currentRevision
      ) {
        db.exec("ROLLBACK;");
        return checkpointFailure(
          "suite_checkpoint_stale_revision",
          "suite checkpoint revision is stale",
          "resume_same_suite",
          {
            expectedRevision: checkpoint.expectedRevision,
            currentRevision,
            suiteRunId: checkpoint.suiteRunId,
          },
        );
      }
      if (
        existing &&
        typeof existing.status === "string" &&
        isTerminalSuiteStatus(existing.status) &&
        existing.status !== checkpoint.status
      ) {
        db.exec("ROLLBACK;");
        return checkpointFailure(
          "suite_state_transition_invalid",
          "a terminal suite checkpoint cannot be advanced",
          "resume_same_suite",
          { suiteRunId: checkpoint.suiteRunId, status: existing.status },
        );
      }
      const nextRevision = currentRevision + 1;
      db.prepare(
        `INSERT INTO suite_runs (project_name, suite_run_id, execution_profile, status, next_plan_order, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json, owner_id, lease_expires_at_epoch_ms, revision, started_at_epoch_ms, updated_at_epoch_ms, completed_at_epoch_ms, reason_code)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(project_name, suite_run_id) DO UPDATE SET execution_profile = excluded.execution_profile, status = excluded.status, next_plan_order = excluded.next_plan_order, active_plan_name = excluded.active_plan_name, active_plan_order = excluded.active_plan_order, active_run_id = excluded.active_run_id, active_phase = excluded.active_phase, continuation_json = excluded.continuation_json, owner_id = excluded.owner_id, lease_expires_at_epoch_ms = excluded.lease_expires_at_epoch_ms, revision = excluded.revision, updated_at_epoch_ms = excluded.updated_at_epoch_ms, completed_at_epoch_ms = excluded.completed_at_epoch_ms, reason_code = excluded.reason_code`,
      ).run(
        args.store.projectName,
        checkpoint.suiteRunId.trim(),
        checkpoint.executionProfile.trim(),
        checkpoint.status,
        checkpoint.nextPlanOrder ?? null,
        checkpoint.activePlanName ?? null,
        checkpoint.activePlanOrder ?? null,
        checkpoint.activeRunId ?? null,
        checkpoint.activePhase ?? null,
        checkpoint.continuation ? JSON.stringify(checkpoint.continuation) : null,
        checkpoint.ownerId ?? null,
        checkpoint.leaseExpiresAtEpochMs ?? null,
        nextRevision,
        checkpoint.startedAtEpochMs,
        checkpoint.updatedAtEpochMs,
        checkpoint.completedAtEpochMs ?? null,
        checkpoint.reasonCode ?? null,
      );
      const suiteRow = db
        .prepare("SELECT suite_run_pk FROM suite_runs WHERE project_name = ? AND suite_run_id = ?")
        .get(args.store.projectName, checkpoint.suiteRunId);
      if (typeof suiteRow?.suite_run_pk !== "number")
        throw new Error("suite_checkpoint_missing_after_upsert");
      for (const planRun of args.planRuns) {
        if (
          !planRun.planName.trim() ||
          !planRun.runId.trim() ||
          !isSafeRelativePath(planRun.runDirPathRel)
        ) {
          db.exec("ROLLBACK;");
          return checkpointFailure(
            "suite_checkpoint_invalid",
            "plan-run identity and workspace-relative Artifact path are required",
            "correct_checkpoint_input",
          );
        }
        db.prepare(
          `INSERT INTO plan_runs (suite_run_pk, project_name, plan_name, run_id, plan_order, status, step_count, failed_step_count, started_at_epoch_ms, completed_at_epoch_ms, revision, reason_code, run_dir_path_rel) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?) ON CONFLICT(project_name, plan_name, run_id) DO UPDATE SET suite_run_pk = excluded.suite_run_pk, plan_order = excluded.plan_order, status = excluded.status, step_count = excluded.step_count, failed_step_count = excluded.failed_step_count, started_at_epoch_ms = excluded.started_at_epoch_ms, completed_at_epoch_ms = excluded.completed_at_epoch_ms, revision = plan_runs.revision + 1, reason_code = excluded.reason_code, run_dir_path_rel = excluded.run_dir_path_rel`,
        ).run(
          suiteRow.suite_run_pk,
          args.store.projectName,
          planRun.planName.trim(),
          planRun.runId.trim(),
          planRun.planOrder ?? null,
          planRun.status,
          planRun.stepCount ?? null,
          planRun.failedStepCount ?? null,
          planRun.startedAtEpochMs ?? null,
          planRun.completedAtEpochMs ?? null,
          planRun.reasonCode ?? null,
          planRun.runDirPathRel.replaceAll("\\", "/"),
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
    return checkpointFailure(
      "run_state_persist_failed",
      "suite checkpoint could not be persisted",
      "retry_state_store",
      {
        error: error instanceof Error ? error.message : String(error),
        suiteRunId: checkpoint.suiteRunId,
      },
    );
  }
}

export function readRegressionSuiteCheckpoint(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
}): PersistedRegressionSuiteCheckpoint | null {
  const row = args.store.database
    .prepare(
      `SELECT suite_run_id, execution_profile, status, revision, started_at_epoch_ms, updated_at_epoch_ms, next_plan_order, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json FROM suite_runs WHERE project_name = ? AND suite_run_id = ?`,
    )
    .get(args.store.projectName, args.suiteRunId);
  if (
    !row ||
    typeof row.suite_run_id !== "string" ||
    typeof row.execution_profile !== "string" ||
    typeof row.status !== "string" ||
    typeof row.revision !== "number" ||
    typeof row.started_at_epoch_ms !== "number" ||
    typeof row.updated_at_epoch_ms !== "number"
  )
    return null;
  let continuation: Record<string, unknown> | undefined;
  if (typeof row.continuation_json === "string") {
    try {
      const parsed = JSON.parse(row.continuation_json) as unknown;
      if (typeof parsed === "object" && parsed !== null && !Array.isArray(parsed))
        continuation = parsed as Record<string, unknown>;
    } catch {
      return null;
    }
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
    ...(typeof row.active_plan_order === "number"
      ? { activePlanOrder: row.active_plan_order }
      : {}),
    ...(typeof row.active_run_id === "string" ? { activeRunId: row.active_run_id } : {}),
    ...(row.active_phase === "trigger" ||
    row.active_phase === "watchers" ||
    row.active_phase === "external_verification"
      ? { activePhase: row.active_phase }
      : {}),
    ...(continuation ? { continuation } : {}),
  };
}

export function readRegressionSuiteState(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
}): PersistedRegressionSuiteState | null {
  const checkpoint = readRegressionSuiteCheckpoint(args);
  if (!checkpoint) return null;
  const suiteRow = args.store.database
    .prepare("SELECT suite_run_pk FROM suite_runs WHERE project_name = ? AND suite_run_id = ?")
    .get(args.store.projectName, args.suiteRunId);
  if (typeof suiteRow?.suite_run_pk !== "number") return null;
  const rows = args.store.database
    .prepare(
      "SELECT plan_name, run_id, plan_order, status, completed_at_epoch_ms, reason_code, run_dir_path_rel FROM plan_runs WHERE suite_run_pk = ? AND project_name = ? ORDER BY plan_order, plan_name, run_id",
    )
    .all(suiteRow.suite_run_pk, args.store.projectName);
  const planRuns: RegressionPlanRunProjection[] = [];
  for (const row of rows) {
    if (
      typeof row.plan_name !== "string" ||
      typeof row.run_id !== "string" ||
      (row.status !== "executed" && row.status !== "blocked" && row.status !== "skipped") ||
      typeof row.run_dir_path_rel !== "string"
    )
      return null;
    planRuns.push({
      planName: row.plan_name,
      runId: row.run_id,
      status: row.status,
      runDirPathRel: row.run_dir_path_rel,
      ...(typeof row.plan_order === "number" ? { planOrder: row.plan_order } : {}),
      ...(row.status === "executed" && typeof row.completed_at_epoch_ms !== "number"
        ? { runStatus: "in_progress" }
        : {}),
      ...(row.status !== "executed" && typeof row.reason_code === "string"
        ? { reasonCode: row.reason_code }
        : {}),
    });
  }
  return { checkpoint, planRuns };
}

export function acquireRegressionSuiteLease(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
  ownerId: string;
  nowEpochMs: number;
  leaseDurationMs: number;
}): AcquireRegressionSuiteLeaseResult {
  if (
    !args.suiteRunId.trim() ||
    !args.ownerId.trim() ||
    !Number.isInteger(args.nowEpochMs) ||
    !Number.isInteger(args.leaseDurationMs) ||
    args.leaseDurationMs <= 0
  )
    return checkpointFailure(
      "suite_checkpoint_invalid",
      "suite lease identity and bounded duration are required",
      "correct_checkpoint_input",
    );
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db
        .prepare(
          "SELECT owner_id, lease_expires_at_epoch_ms, revision FROM suite_runs WHERE project_name = ? AND suite_run_id = ?",
        )
        .get(args.store.projectName, args.suiteRunId);
      if (!existing || typeof existing.revision !== "number") {
        db.exec("ROLLBACK;");
        return checkpointFailure(
          "suite_checkpoint_invalid",
          "suite checkpoint must exist before lease acquisition",
          "resume_same_suite",
        );
      }
      const ownerId = typeof existing.owner_id === "string" ? existing.owner_id : undefined;
      const expiry =
        typeof existing.lease_expires_at_epoch_ms === "number"
          ? existing.lease_expires_at_epoch_ms
          : undefined;
      if (
        ownerId &&
        ownerId !== args.ownerId &&
        typeof expiry === "number" &&
        expiry > args.nowEpochMs
      ) {
        db.exec("ROLLBACK;");
        return checkpointFailure(
          "suite_checkpoint_owner_active",
          "another caller currently owns the suite checkpoint",
          "resume_same_suite",
          { suiteRunId: args.suiteRunId, leaseExpiresAtEpochMs: expiry },
        );
      }
      const nextRevision = existing.revision + 1;
      const leaseExpiresAtEpochMs = args.nowEpochMs + args.leaseDurationMs;
      db.prepare(
        "UPDATE suite_runs SET owner_id = ?, lease_expires_at_epoch_ms = ?, revision = ?, updated_at_epoch_ms = ? WHERE project_name = ? AND suite_run_id = ?",
      ).run(
        args.ownerId,
        leaseExpiresAtEpochMs,
        nextRevision,
        args.nowEpochMs,
        args.store.projectName,
        args.suiteRunId,
      );
      db.exec("COMMIT;");
      return { ok: true, revision: nextRevision, leaseExpiresAtEpochMs };
    } catch (error) {
      try {
        db.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      throw error;
    }
  } catch (error) {
    return checkpointFailure(
      "run_state_persist_failed",
      "suite lease could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}

export function releaseRegressionSuiteLease(args: {
  store: OpenRunStateStore;
  suiteRunId: string;
  ownerId: string;
  nowEpochMs: number;
}): void {
  args.store.database
    .prepare(
      "UPDATE suite_runs SET owner_id = NULL, lease_expires_at_epoch_ms = NULL, updated_at_epoch_ms = ? WHERE project_name = ? AND suite_run_id = ? AND owner_id = ?",
    )
    .run(args.nowEpochMs, args.store.projectName, args.suiteRunId, args.ownerId);
}

function isSafeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !path.isAbsolute(pathRel) &&
    !path.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}
