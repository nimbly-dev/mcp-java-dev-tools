import path from "node:path";
import type {
  OpenRunStateStore,
  RunStateArtifactLink,
  RunStateStoreFailure,
  RunStateStoreFailureCode,
} from "./model/run_state_store.model";

function failure(
  reasonCode: RunStateStoreFailureCode,
  reason: string,
  nextAction: RunStateStoreFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateStoreFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function isSafeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !path.isAbsolute(pathRel) &&
    !path.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}

/** Records portable canonical Artifact linkage; runtime behavior remains owned by Regression Suite. */
export function upsertRunStateArtifact(
  store: OpenRunStateStore,
  artifact: RunStateArtifactLink,
): RunStateStoreFailure | { ok: true } {
  if (!isSafeRelativePath(artifact.pathRel)) {
    return failure(
      "state_store_path_invalid",
      "Artifact path must be workspace-relative and may not traverse parents",
      "correct_state_store_input",
      { pathRel: artifact.pathRel },
    );
  }
  try {
    store.database
      .prepare(
        `INSERT INTO artifacts (project_name, plan_name, run_id, suite_run_id, artifact_kind, path_rel, checksum, created_at_epoch_ms)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(project_name, artifact_kind, path_rel) DO UPDATE SET
        plan_name = excluded.plan_name, run_id = excluded.run_id, suite_run_id = excluded.suite_run_id,
        checksum = excluded.checksum, created_at_epoch_ms = excluded.created_at_epoch_ms`,
      )
      .run(
        store.projectName,
        artifact.planName ?? null,
        artifact.runId ?? null,
        artifact.suiteRunId ?? null,
        artifact.artifactKind,
        artifact.pathRel.replaceAll("\\", "/"),
        artifact.checksum ?? null,
        artifact.createdAtEpochMs,
      );
    return { ok: true };
  } catch (error) {
    return failure(
      "state_store_open_failed",
      "run-state Artifact linkage could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}
