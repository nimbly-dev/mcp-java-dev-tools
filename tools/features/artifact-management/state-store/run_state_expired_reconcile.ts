import fs from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";
import { openRunStateStore } from "./run_state_store";
import { readRunStateCutoverStatus } from "./state_store_cutover";
import type { RunStateStoreFailure } from "./model/run_state_store.model";

type ReconcileFailure = {
  ok: false;
  reasonCode: string;
  reason: string;
  nextAction: string;
  reasonMeta?: Record<string, unknown>;
};

export type ReconcileExpiredActiveStateResult =
  | ReconcileFailure
  | {
      ok: true;
      reconciled: false;
      suiteRunId: string;
      reasonCode?: string;
    }
  | {
      ok: true;
      reconciled: true;
      suiteRunId: string;
      suite: Record<string, unknown>;
    };

function failure(
  reasonCode: string,
  reason: string,
  nextAction: string,
  reasonMeta?: Record<string, unknown>,
): ReconcileFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function safeRelativePath(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    !path.isAbsolute(value) &&
    !path.win32.isAbsolute(value) &&
    !value.split(/[\\/]/).includes("..")
  );
}

function canonicalArtifactPath(
  workspaceRootAbs: string,
  projectName: string,
  pathRel: unknown,
): string | undefined {
  if (!safeRelativePath(pathRel)) return undefined;
  const projectRootAbs = path.resolve(workspaceRootAbs, ".mcpjvm", projectName);
  const resolved = path.resolve(workspaceRootAbs, pathRel);
  return resolved === projectRootAbs || resolved.startsWith(`${projectRootAbs}${path.sep}`)
    ? resolved
    : undefined;
}

async function sha256File(filePathAbs: string): Promise<string | undefined> {
  try {
    const hash = createHash("sha256");
    hash.update(await fs.readFile(filePathAbs));
    return hash.digest("hex");
  } catch {
    return undefined;
  }
}

async function validateArtifactLink(
  workspaceRootAbs: string,
  projectName: string,
  link: Record<string, unknown> | undefined,
): Promise<string | undefined> {
  if (!link) return undefined;
  const pathAbs = canonicalArtifactPath(workspaceRootAbs, projectName, link.path_rel);
  if (!pathAbs) return undefined;
  const stat = await fs.stat(pathAbs).catch(() => undefined);
  if (!stat?.isFile()) return undefined;
  if (typeof link.checksum === "string" && link.checksum.length > 0) {
    const checksum = await sha256File(pathAbs);
    if (checksum !== link.checksum) return undefined;
  }
  return pathAbs;
}

async function readJson(filePathAbs: string): Promise<Record<string, unknown> | null> {
  try {
    const parsed = JSON.parse(await fs.readFile(filePathAbs, "utf8")) as unknown;
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

async function writeJsonAtomic(filePathAbs: string, value: Record<string, unknown>): Promise<void> {
  const tempPath = `${filePathAbs}.reconcile-${process.pid}-${Date.now()}.tmp`;
  await fs.writeFile(tempPath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await fs.rename(tempPath, filePathAbs);
}

function cloneJson(value: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}

/**
 * Reconciles one overdue active Watcher only while its owning resume holds the
 * suite lease. This is intentionally separate from retention and query code.
 */
export async function reconcileExpiredActiveState(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  suiteRunId: string;
  ownerId: string;
  nowEpochMs: number;
}): Promise<ReconcileExpiredActiveStateResult> {
  const store = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!store.ok) return store;
  let changedFiles: Array<{ path: string; original: Record<string, unknown> }> = [];
  try {
    if (readRunStateCutoverStatus({ store }) !== "cutover_complete") {
      return { ok: true, reconciled: false, suiteRunId: args.suiteRunId };
    }
    try {
      const suite = store.database
        .prepare(
          "SELECT suite_run_id, execution_profile, status, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json, owner_id, lease_expires_at_epoch_ms, revision FROM suite_runs WHERE project_name = ? AND suite_run_id = ?",
        )
        .get(args.projectName, args.suiteRunId);
      if (!suite || suite.execution_profile !== args.executionProfile)
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId, reasonCode: "suite_resume_identity_mismatch" };
      if (suite.status !== "in_progress" || suite.active_phase !== "watchers")
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId };
      if (suite.owner_id !== args.ownerId || typeof suite.lease_expires_at_epoch_ms !== "number" || suite.lease_expires_at_epoch_ms <= args.nowEpochMs)
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId, reasonCode: "suite_checkpoint_owner_active" };
      if (typeof suite.active_plan_name !== "string" || typeof suite.active_run_id !== "string" || typeof suite.revision !== "number")
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId };
      const watchers = store.database
        .prepare(
          "SELECT watcher_run_pk, watcher_name, status, deadline_at_epoch_ms, started_at_epoch_ms, attempt_count, revision, artifact_path_rel FROM watcher_runs WHERE project_name = ? AND plan_name = ? AND run_id = ? AND suite_run_id = ? AND status = 'in_progress'",
        )
        .all(args.projectName, suite.active_plan_name, suite.active_run_id, args.suiteRunId);
      // There must be one unambiguous active Watcher for this exact checkpoint.
      // Never guess which active row should be terminalized.
      if (watchers.length !== 1)
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId };
      const watcher = watchers[0];
      if (!watcher || typeof watcher.deadline_at_epoch_ms !== "number" || watcher.deadline_at_epoch_ms > args.nowEpochMs)
        return { ok: true, reconciled: false, suiteRunId: args.suiteRunId };
      const runDirPathRel = safeRelativePath(watcher.artifact_path_rel)
        ? watcher.artifact_path_rel
        : store.database
            .prepare(
              "SELECT run_dir_path_rel FROM plan_runs WHERE project_name = ? AND plan_name = ? AND run_id = ? AND suite_run_pk = (SELECT suite_run_pk FROM suite_runs WHERE project_name = ? AND suite_run_id = ?)",
            )
            .get(args.projectName, suite.active_plan_name, suite.active_run_id, args.projectName, args.suiteRunId)
            ?.run_dir_path_rel;
      if (!safeRelativePath(runDirPathRel))
        return failure("state_store_artifact_link_invalid", "active Watcher Artifact linkage is invalid", "repair_state_store");

      const links = store.database
        .prepare(
          "SELECT artifact_kind, path_rel, checksum FROM artifacts WHERE project_name = ? AND plan_name = ? AND run_id = ? AND suite_run_id = ? AND artifact_kind IN ('execution_result', 'evidence')",
        )
        .all(args.projectName, suite.active_plan_name, suite.active_run_id, args.suiteRunId) as Array<Record<string, unknown>>;
      const executionLinkRow = links.find((entry) => entry.artifact_kind === "execution_result");
      const evidenceLinkRow = links.find((entry) => entry.artifact_kind === "evidence");
      // Live run projections normally carry the canonical run directory on the
      // Watcher row; older/rebuilt stores may also have explicit file links.
      // An explicit stale link is never replaced by this fallback.
      const executionPath = executionLinkRow
        ? await validateArtifactLink(args.workspaceRootAbs, args.projectName, executionLinkRow)
        : canonicalArtifactPath(
            args.workspaceRootAbs,
            args.projectName,
            `${runDirPathRel}/execution.result.json`,
          );
      const evidencePath = evidenceLinkRow
        ? await validateArtifactLink(args.workspaceRootAbs, args.projectName, evidenceLinkRow)
        : canonicalArtifactPath(
            args.workspaceRootAbs,
            args.projectName,
            `${runDirPathRel}/evidence.json`,
          );
      if (!executionPath || !evidencePath)
        return failure("state_store_artifact_link_invalid", "canonical Watcher Artifact linkage is missing", "repair_state_store");
      const execution = await readJson(executionPath);
      const evidence = await readJson(evidencePath);
      if (!execution || !evidence)
        return failure("state_store_artifact_link_invalid", "canonical Watcher Artifacts are missing or invalid", "repair_state_store");
      const executionWatchers = Array.isArray(execution.watchers)
        ? (execution.watchers as unknown[])
        : [];
      const evidenceWatchers = Array.isArray(evidence.watcherExecutions)
        ? (evidence.watcherExecutions as unknown[])
        : [];
      execution.watchers = executionWatchers;
      evidence.watcherExecutions = evidenceWatchers;
      const watcherName = watcher.watcher_name;
      let executionPolicy: "stop_on_fail" | "continue_on_fail" = "stop_on_fail";
      try {
        const continuation = JSON.parse(String(suite.continuation_json ?? "")) as {
          runtimeSuite?: { executionPolicy?: unknown };
        };
        if (continuation.runtimeSuite?.executionPolicy === "continue_on_fail")
          executionPolicy = "continue_on_fail";
      } catch {
        // Keep the established fail-closed default when continuation metadata is invalid.
      }
      let executionWatcher = executionWatchers.find((entry) => entry && typeof entry === "object" && (entry as Record<string, unknown>).id === watcherName) as Record<string, unknown> | undefined;
      let evidenceWatcher = evidenceWatchers.find((entry) => entry && typeof entry === "object" && (entry as Record<string, unknown>).id === watcherName) as Record<string, unknown> | undefined;
      // An in-progress checkpoint can legitimately persist only the continuation;
      // materialize the exact Watcher projection when terminalizing it.
      if (!executionWatcher) {
        executionWatcher = { id: watcherName };
        executionWatchers.push(executionWatcher);
      }
      if (!evidenceWatcher) {
        evidenceWatcher = { id: watcherName };
        evidenceWatchers.push(evidenceWatcher);
      }
      store.database.exec("BEGIN IMMEDIATE;");
      const lockedState = store.database
        .prepare(
          "SELECT sr.revision AS suite_revision, wr.revision AS watcher_revision FROM suite_runs sr JOIN plan_runs pr ON pr.suite_run_pk = sr.suite_run_pk JOIN watcher_runs wr ON wr.plan_run_pk = pr.plan_run_pk WHERE sr.project_name = ? AND sr.suite_run_id = ? AND pr.plan_name = ? AND pr.run_id = ? AND wr.watcher_run_pk = ?",
        )
        .get(args.projectName, args.suiteRunId, suite.active_plan_name, suite.active_run_id, watcher.watcher_run_pk);
      if (lockedState?.suite_revision !== suite.revision || lockedState?.watcher_revision !== watcher.revision)
        throw new Error("state_store_reconcile_revision_conflict");
      changedFiles = [
        { path: executionPath, original: cloneJson(execution) },
        { path: evidencePath, original: cloneJson(evidence) },
      ];
      const completedAt = args.nowEpochMs;
      Object.assign(executionWatcher, {
        status: "blocked_runtime",
        outcome: "timed_out",
        reasonCode: "watcher_timeout",
        completedAtEpochMs: completedAt,
        durationMs: Math.max(1, completedAt - Number(watcher.started_at_epoch_ms)),
      });
      delete execution.continuation;
      execution.status = "blocked";
      execution.watcherStatus = "blocked";
      execution.endedAt = new Date(completedAt).toISOString();
      Object.assign(evidenceWatcher, {
        status: "timed_out",
        outcome: "timeout",
        reasonCode: "watcher_timeout",
      });
      await writeJsonAtomic(executionPath, execution);
      await writeJsonAtomic(evidencePath, evidence);

      store.database
        .prepare(
          "UPDATE suite_runs SET status = 'blocked', active_phase = NULL, continuation_json = NULL, completed_at_epoch_ms = ?, reason_code = 'watcher_timeout', owner_id = NULL, lease_expires_at_epoch_ms = NULL, revision = revision + 1, updated_at_epoch_ms = ? WHERE project_name = ? AND suite_run_id = ? AND revision = ? AND owner_id = ? AND status = 'in_progress'",
        )
        .run(completedAt, completedAt, args.projectName, args.suiteRunId, suite.revision, args.ownerId);
      const suiteChanges = Number(store.database.prepare("SELECT changes() AS changes").get()?.changes ?? 0);
      store.database
        .prepare(
          "UPDATE watcher_runs SET status = 'blocked_runtime', outcome = 'timed_out', reason_code = 'watcher_timeout', completed_at_epoch_ms = ?, continuation_json = NULL, revision = revision + 1 WHERE watcher_run_pk = ? AND revision = ? AND status = 'in_progress'",
        )
        .run(completedAt, watcher.watcher_run_pk, watcher.revision);
      const watcherChanges = Number(store.database.prepare("SELECT changes() AS changes").get()?.changes ?? 0);
      store.database
        .prepare(
          "UPDATE plan_runs SET completed_at_epoch_ms = ?, reason_code = 'watcher_timeout' WHERE project_name = ? AND plan_name = ? AND run_id = ? AND completed_at_epoch_ms IS NULL",
        )
        .run(completedAt, args.projectName, suite.active_plan_name, suite.active_run_id);
      if (suiteChanges !== 1 || watcherChanges !== 1) {
        throw new Error("state_store_reconcile_revision_conflict");
      }
      store.database.exec("COMMIT;");
      return {
        ok: true,
        reconciled: true,
        suiteRunId: args.suiteRunId,
        suite: {
          executionProfile: args.executionProfile,
          executionPolicy,
          status: "blocked",
          suiteRunId: args.suiteRunId,
          reasonCode: "watcher_timeout",
          planRuns: [{
            planName: suite.active_plan_name,
            runId: suite.active_run_id,
            status: "executed",
            runStatus: "blocked",
            order:
              typeof suite.active_plan_order === "number"
                ? suite.active_plan_order
                : 0,
          }],
          progressSummary: { progressState: "terminal" },
        },
      };
    } catch (error) {
      try { store.database.exec("ROLLBACK;"); } catch { /* transaction already closed */ }
      for (const file of changedFiles) {
        try { await writeJsonAtomic(file.path, file.original); } catch { /* preserve fail-closed result */ }
      }
      if (error instanceof Error && error.message === "state_store_reconcile_revision_conflict")
        return failure("suite_checkpoint_stale_revision", "suite checkpoint changed during reconciliation", "resume_same_suite");
      return failure("state_store_open_failed", "expired Watcher reconciliation failed", "retry_state_store", { error: error instanceof Error ? error.message : String(error) });
    }
  } finally {
    store.close();
  }
}
