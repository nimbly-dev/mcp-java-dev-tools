import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { promises as fs } from "node:fs";
import path from "node:path";
import { openRunStateStore } from "./run_state_store";
import type {
  OpenRunStateStore,
  RunStateDatabase,
  RunStateStoreFailure,
} from "./model/run_state_store.model";

const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_REASONS = 100;
const RETENTION_LOCK_LEASE_MS = 30 * 60 * 1000;

export type RetentionCleanupInput = {
  workspaceRootAbs: string;
  projectName: string;
  retention?: {
    terminalOlderThanDays?: number;
    keepMostRecentTerminalRuns?: number;
    dryRun?: boolean;
    maxDeleteBatch?: number;
  };
};

type RetentionPolicy = {
  terminalOlderThanDays: number;
  keepMostRecentTerminalRuns: number;
  dryRun: boolean;
  maxDeleteBatch: number;
};

type CleanupReason = { reasonCode: string; count: number };
type Candidate = {
  planRunPk: number;
  suiteRunPk?: number;
  planName: string;
  runId: string;
  completedAtEpochMs: number;
  status: string;
};
type RetentionFailureCode =
  | "state_store_retention_invalid"
  | "state_store_retention_not_ready"
  | "state_store_retention_conflict"
  | "state_store_retention_active_state"
  | "state_store_retention_artifact_link_missing"
  | "state_store_retention_artifact_link_stale"
  | "state_store_retention_failed"
  | "state_store_locked"
  | "state_store_corrupt"
  | "state_store_schema_unsupported";
type RetentionLock = { pathAbs: string; cleanupId: string };

export type RetentionCleanupSummary = {
  outcome: "dry_run" | "completed" | "batch_limited";
  scannedRuns: number;
  policyCandidateRuns: number;
  eligibleRuns: number;
  deletedRuns: number;
  skippedActive: number;
  skippedArtifactLink: number;
  retainedByAge: number;
  retainedByCount: number;
  reasons: CleanupReason[];
  batchLimited?: true;
  remainingEligibleRuns?: number;
};

export type RetentionCleanupResult =
  | { ok: true; projectName: string; cleanupId: string; summary: RetentionCleanupSummary }
  | {
      ok: false;
      reasonCode: RetentionFailureCode;
      reason: string;
      nextAction: string;
      reasonMeta?: Record<string, unknown>;
    };

function failure(
  reasonCode: RetentionFailureCode,
  reason: string,
  nextAction: string,
  reasonMeta?: Record<string, unknown>,
): RetentionCleanupResult {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function policyFromInput(input: RetentionCleanupInput): RetentionPolicy | RetentionCleanupResult {
  const retention = input.retention ?? {};
  const policy: RetentionPolicy = {
    terminalOlderThanDays: retention.terminalOlderThanDays ?? 90,
    keepMostRecentTerminalRuns: retention.keepMostRecentTerminalRuns ?? 1000,
    dryRun: retention.dryRun ?? true,
    maxDeleteBatch: retention.maxDeleteBatch ?? 500,
  };
  if (
    !Number.isInteger(policy.terminalOlderThanDays) ||
    policy.terminalOlderThanDays < 1 ||
    policy.terminalOlderThanDays > 3650 ||
    !Number.isInteger(policy.keepMostRecentTerminalRuns) ||
    policy.keepMostRecentTerminalRuns < 0 ||
    policy.keepMostRecentTerminalRuns > 100000 ||
    !Number.isInteger(policy.maxDeleteBatch) ||
    policy.maxDeleteBatch < 1 ||
    policy.maxDeleteBatch > 500
  )
    return failure(
      "state_store_retention_invalid",
      "retention values are outside their supported bounds",
      "correct_retention_input",
    );
  return policy;
}

function addReason(reasons: Map<string, number>, reasonCode: string): void {
  reasons.set(reasonCode, (reasons.get(reasonCode) ?? 0) + 1);
}

async function acquireRetentionLock(
  workspaceRootAbs: string,
  projectName: string,
  cleanupId: string,
): Promise<RetentionLock | undefined> {
  const projectDirAbs = path.resolve(workspaceRootAbs, ".mcpjvm", projectName);
  const pathAbs = path.join(projectDirAbs, "state-store-retention.lock");
  const payload = JSON.stringify({
    cleanupId,
    expiresAtEpochMs: Date.now() + RETENTION_LOCK_LEASE_MS,
  });
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const handle = await fs.open(pathAbs, "wx");
      try {
        await handle.writeFile(payload, "utf8");
      } finally {
        await handle.close();
      }
      return { pathAbs, cleanupId };
    } catch (error) {
      if (!(error instanceof Error) || !("code" in error) || error.code !== "EEXIST")
        return undefined;
      const existing = await fs.readFile(pathAbs, "utf8").catch(() => undefined);
      let expiresAtEpochMs = Number.POSITIVE_INFINITY;
      try {
        const parsed = JSON.parse(existing ?? "") as { expiresAtEpochMs?: unknown };
        if (typeof parsed.expiresAtEpochMs === "number") expiresAtEpochMs = parsed.expiresAtEpochMs;
      } catch {
        // An unreadable lock is active; fail closed rather than deleting it.
      }
      if (expiresAtEpochMs > Date.now()) return undefined;
      await fs.unlink(pathAbs).catch(() => undefined);
    }
  }
  return undefined;
}

async function releaseRetentionLock(lock: RetentionLock): Promise<void> {
  const existing = await fs.readFile(lock.pathAbs, "utf8").catch(() => undefined);
  if (!existing?.includes(`"cleanupId":"${lock.cleanupId}"`)) return;
  await fs.unlink(lock.pathAbs).catch(() => undefined);
}

function normalizeCanonicalArtifactPath(
  rootAbs: string,
  projectName: string,
  pathRel: unknown,
): string | undefined {
  if (typeof pathRel !== "string" || pathRel.trim().length === 0) return undefined;
  const normalized = pathRel.replaceAll("\\", "/");
  if (path.isAbsolute(normalized) || normalized.split("/").includes("..")) return undefined;
  const resolved = path.resolve(rootAbs, normalized);
  const canonicalRoot = path.resolve(rootAbs, ".mcpjvm", projectName);
  if (resolved !== canonicalRoot && !resolved.startsWith(`${canonicalRoot}${path.sep}`))
    return undefined;
  return resolved;
}

async function sha256File(filePathAbs: string): Promise<string | undefined> {
  try {
    const hash = createHash("sha256");
    const stream = createReadStream(filePathAbs);
    for await (const chunk of stream) hash.update(chunk);
    return hash.digest("hex");
  } catch {
    return undefined;
  }
}

async function canonicalLinkIsValid(
  workspaceRootAbs: string,
  projectName: string,
  row: Record<string, unknown>,
): Promise<"valid" | "missing" | "stale"> {
  const resolved = normalizeCanonicalArtifactPath(workspaceRootAbs, projectName, row.path_rel);
  if (!resolved) return "stale";
  const stat = await fs.stat(resolved).catch(() => undefined);
  if (!stat || !stat.isFile()) return "stale";
  if (typeof row.checksum === "string" && row.checksum.length > 0) {
    const checksum = await sha256File(resolved);
    if (!checksum || checksum !== row.checksum) return "stale";
  }
  return "valid";
}

function terminalCandidates(database: RunStateDatabase, projectName: string): Candidate[] {
  return database
    .prepare(
      `SELECT plan_run_pk, suite_run_pk, plan_name, run_id, completed_at_epoch_ms, status
       FROM plan_runs
       WHERE project_name = ? AND completed_at_epoch_ms IS NOT NULL AND status <> 'in_progress'
       ORDER BY completed_at_epoch_ms ASC, plan_run_pk ASC`,
    )
    .all(projectName)
    .map((row) => ({
      planRunPk: Number(row.plan_run_pk),
      ...(row.suite_run_pk === null || row.suite_run_pk === undefined
        ? {}
        : { suiteRunPk: Number(row.suite_run_pk) }),
      planName: String(row.plan_name),
      runId: String(row.run_id),
      completedAtEpochMs: Number(row.completed_at_epoch_ms),
      status: String(row.status),
    }));
}

function overdueActivePlanRunPks(
  database: RunStateDatabase,
  projectName: string,
  nowEpochMs: number,
): Set<number> {
  const rows = database
    .prepare(
      `SELECT DISTINCT pr.plan_run_pk
       FROM suite_runs sr
       JOIN plan_runs pr ON pr.suite_run_pk = sr.suite_run_pk
         AND pr.project_name = sr.project_name
         AND pr.plan_name = sr.active_plan_name
         AND pr.run_id = sr.active_run_id
       JOIN watcher_runs wr ON wr.plan_run_pk = pr.plan_run_pk
       WHERE sr.project_name = ?
         AND sr.status = 'in_progress'
         AND wr.status = 'in_progress'
         AND wr.deadline_at_epoch_ms IS NOT NULL
         AND wr.deadline_at_epoch_ms <= ?`,
    )
    .all(projectName, nowEpochMs) as Array<Record<string, unknown>>;
  return new Set(rows.map((row) => Number(row.plan_run_pk)).filter(Number.isInteger));
}

async function candidateSafety(
  store: OpenRunStateStore,
  workspaceRootAbs: string,
  candidate: Candidate,
  now: number,
): Promise<"active" | "expired_active_state" | "artifact_missing" | "artifact_stale" | "eligible"> {
  const suite =
    candidate.suiteRunPk === undefined
      ? undefined
      : store.database
          .prepare(
            `SELECT status, lease_expires_at_epoch_ms, active_run_id
         FROM suite_runs WHERE suite_run_pk = ? AND project_name = ?`,
          )
          .get(candidate.suiteRunPk, store.projectName);
  const watcher = store.database
    .prepare(
      `SELECT 1 AS active FROM watcher_runs
       WHERE plan_run_pk = ? AND status = 'in_progress'
       LIMIT 1`,
    )
    .get(candidate.planRunPk);
  if (suite?.status === "in_progress") {
    const overdue = store.database
      .prepare(
        "SELECT 1 AS overdue FROM watcher_runs WHERE plan_run_pk = ? AND status = 'in_progress' AND deadline_at_epoch_ms <= ? LIMIT 1",
      )
      .get(candidate.planRunPk, now);
    return overdue ? "expired_active_state" : "active";
  }
  if (
    suite?.active_run_id === candidate.runId ||
    (typeof suite?.lease_expires_at_epoch_ms === "number" &&
      suite.lease_expires_at_epoch_ms > now) ||
    watcher
  )
    return "active";

  const links = store.database
    .prepare(
      `SELECT path_rel, checksum FROM artifacts
       WHERE project_name = ? AND plan_name = ? AND run_id = ?
       ORDER BY artifact_id ASC`,
    )
    .all(store.projectName, candidate.planName, candidate.runId);
  if (links.length === 0) return "artifact_missing";
  for (const link of links) {
    const result = await canonicalLinkIsValid(workspaceRootAbs, store.projectName, link);
    if (result === "missing") return "artifact_missing";
    if (result === "stale") return "artifact_stale";
  }
  return "eligible";
}

function deleteCandidate(
  database: RunStateDatabase,
  projectName: string,
  candidate: Candidate,
): void {
  database
    .prepare(
      `DELETE FROM watcher_attempts WHERE watcher_run_pk IN
     (SELECT watcher_run_pk FROM watcher_runs WHERE plan_run_pk = ?)`,
    )
    .run(candidate.planRunPk);
  database.prepare("DELETE FROM watcher_runs WHERE plan_run_pk = ?").run(candidate.planRunPk);
  database
    .prepare(
      `DELETE FROM external_verification_assertions WHERE external_verification_pk IN
     (SELECT external_verification_pk FROM external_verifications WHERE plan_run_pk = ?)`,
    )
    .run(candidate.planRunPk);
  database
    .prepare("DELETE FROM external_verifications WHERE plan_run_pk = ?")
    .run(candidate.planRunPk);
  database
    .prepare(
      `DELETE FROM correlation_probe_observations WHERE line_expectation_pk IN
     (SELECT line_expectation_pk FROM correlation_line_expectations WHERE correlation_run_pk IN
       (SELECT correlation_run_pk FROM correlation_runs WHERE project_name = ? AND plan_name = ? AND run_id = ?))`,
    )
    .run(projectName, candidate.planName, candidate.runId);
  database
    .prepare(
      `DELETE FROM correlation_line_expectations WHERE correlation_run_pk IN
     (SELECT correlation_run_pk FROM correlation_runs WHERE project_name = ? AND plan_name = ? AND run_id = ?)`,
    )
    .run(projectName, candidate.planName, candidate.runId);
  database
    .prepare(
      `DELETE FROM correlation_keys WHERE correlation_run_pk IN
     (SELECT correlation_run_pk FROM correlation_runs WHERE project_name = ? AND plan_name = ? AND run_id = ?)`,
    )
    .run(projectName, candidate.planName, candidate.runId);
  database
    .prepare("DELETE FROM correlation_runs WHERE project_name = ? AND plan_name = ? AND run_id = ?")
    .run(projectName, candidate.planName, candidate.runId);
  database
    .prepare("DELETE FROM artifacts WHERE project_name = ? AND plan_name = ? AND run_id = ?")
    .run(projectName, candidate.planName, candidate.runId);
  database.prepare("DELETE FROM plan_runs WHERE plan_run_pk = ?").run(candidate.planRunPk);
  if (candidate.suiteRunPk !== undefined)
    database
      .prepare(
        `DELETE FROM suite_runs WHERE suite_run_pk = ?
       AND NOT EXISTS (SELECT 1 FROM plan_runs WHERE suite_run_pk = ?)`,
      )
      .run(candidate.suiteRunPk, candidate.suiteRunPk);
}

export async function cleanupRunStateRetention(
  input: RetentionCleanupInput,
): Promise<RetentionCleanupResult> {
  if (
    !input.projectName ||
    input.projectName.trim().length === 0 ||
    input.projectName.trim() !== input.projectName ||
    input.projectName === "." ||
    input.projectName === ".." ||
    /[\\/]/.test(input.projectName)
  )
    return failure(
      "state_store_retention_invalid",
      "projectName is required",
      "correct_retention_input",
    );
  const policyResult = policyFromInput(input);
  if (!("terminalOlderThanDays" in policyResult)) return policyResult;
  const projectName = input.projectName.trim();
  const databasePathAbs = path.resolve(
    input.workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "run-state.sqlite",
  );
  const databaseExists = await fs
    .stat(databasePathAbs)
    .then(() => true)
    .catch(() => false);
  if (!databaseExists)
    return failure(
      "state_store_retention_not_ready",
      "run-state SQLite store is not cut over",
      "run_state_store_cutover",
    );
  const cleanupId = randomUUID();
  const lock = await acquireRetentionLock(input.workspaceRootAbs, projectName, cleanupId);
  if (!lock)
    return failure(
      "state_store_retention_conflict",
      "another retention cleanup is active for this project",
      "retry_retention_cleanup",
    );
  const store = await openRunStateStore({ workspaceRootAbs: input.workspaceRootAbs, projectName });
  if (!store.ok) {
    await releaseRetentionLock(lock);
    if (
      store.reasonCode === "state_store_locked" ||
      store.reasonCode === "state_store_corrupt" ||
      store.reasonCode === "state_store_schema_unsupported"
    )
      return failure(store.reasonCode, store.reason, "retry_retention_cleanup", store.reasonMeta);
    return failure(
      "state_store_retention_failed",
      store.reason,
      "retry_retention_cleanup",
      store.reasonMeta,
    );
  }
  const startedAt = Date.now();
  const reasons = new Map<string, number>();
  try {
    const cutover = store.database
      .prepare("SELECT status FROM state_store_cutover WHERE project_name = ?")
      .get(projectName);
    if (cutover?.status !== "cutover_complete")
      return failure(
        "state_store_retention_not_ready",
        "run-state SQLite store is not cut over",
        "run_state_store_cutover",
      );
    store.database.exec("BEGIN IMMEDIATE;");
    const now = Date.now();
    const candidates = terminalCandidates(store.database, projectName);
    const newestFirst = [...candidates].sort(
      (a, b) => b.completedAtEpochMs - a.completedAtEpochMs || b.planRunPk - a.planRunPk,
    );
    const retained = new Set(
      newestFirst.slice(0, policyResult.keepMostRecentTerminalRuns).map((run) => run.planRunPk),
    );
    const threshold = now - policyResult.terminalOlderThanDays * DAY_MS;
    const policyCandidates: Candidate[] = [];
    let retainedByAge = 0;
    for (const candidate of candidates) {
      if (retained.has(candidate.planRunPk)) continue;
      if (candidate.completedAtEpochMs >= threshold) {
        retainedByAge += 1;
        continue;
      }
      policyCandidates.push(candidate);
    }
    const eligible: Candidate[] = [];
    const overdueActive = overdueActivePlanRunPks(store.database, projectName, now);
    let skippedActive = overdueActive.size;
    for (let index = 0; index < overdueActive.size; index += 1)
      addReason(reasons, "expired_active_state");
    let skippedArtifactLink = 0;
    for (const candidate of policyCandidates) {
      const safety = await candidateSafety(store, input.workspaceRootAbs, candidate, now);
      if (safety === "eligible") eligible.push(candidate);
      else if (safety === "active") {
        skippedActive += 1;
        addReason(reasons, "state_store_retention_active_state");
      } else if (safety === "expired_active_state") {
        if (!overdueActive.has(candidate.planRunPk)) {
          skippedActive += 1;
          addReason(reasons, "expired_active_state");
        }
      } else {
        skippedArtifactLink += 1;
        addReason(
          reasons,
          safety === "artifact_missing"
            ? "state_store_retention_artifact_link_missing"
            : "state_store_retention_artifact_link_stale",
        );
      }
    }
    const limited = eligible.length > policyResult.maxDeleteBatch;
    const selected = eligible.slice(0, policyResult.maxDeleteBatch);
    if (!policyResult.dryRun)
      for (const candidate of selected) deleteCandidate(store.database, projectName, candidate);
    const deletedRuns = policyResult.dryRun ? 0 : selected.length;
    const outcome = limited ? "batch_limited" : policyResult.dryRun ? "dry_run" : "completed";
    const completedAt = Date.now();
    const summary: RetentionCleanupSummary = {
      outcome,
      scannedRuns: candidates.length,
      policyCandidateRuns: policyCandidates.length,
      eligibleRuns: eligible.length,
      deletedRuns,
      skippedActive,
      skippedArtifactLink,
      retainedByAge,
      retainedByCount: retained.size,
      reasons: [...reasons.entries()]
        .slice(0, MAX_REASONS)
        .map(([reasonCode, count]) => ({ reasonCode, count })),
      ...(limited
        ? { batchLimited: true, remainingEligibleRuns: eligible.length - deletedRuns }
        : {}),
    };
    store.database
      .prepare(
        `INSERT INTO state_store_cleanup_audits (
        project_name, cleanup_id, started_at_epoch_ms, completed_at_epoch_ms, dry_run,
        terminal_older_than_days, keep_most_recent_terminal_runs, max_delete_batch,
        outcome, reason_code, scanned_runs, policy_candidate_runs, eligible_runs,
        deleted_runs, skipped_active, skipped_artifact_link, retained_by_age,
        retained_by_count, remaining_eligible_runs, reasons_json
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        projectName,
        cleanupId,
        startedAt,
        completedAt,
        policyResult.dryRun ? 1 : 0,
        policyResult.terminalOlderThanDays,
        policyResult.keepMostRecentTerminalRuns,
        policyResult.maxDeleteBatch,
        outcome,
        summary.scannedRuns,
        summary.policyCandidateRuns,
        summary.eligibleRuns,
        summary.deletedRuns,
        summary.skippedActive,
        summary.skippedArtifactLink,
        summary.retainedByAge,
        summary.retainedByCount,
        summary.remainingEligibleRuns ?? 0,
        JSON.stringify(summary.reasons),
      );
    store.database
      .prepare(
        `DELETE FROM state_store_cleanup_audits WHERE project_name = ? AND cleanup_audit_pk NOT IN
       (SELECT cleanup_audit_pk FROM state_store_cleanup_audits WHERE project_name = ?
        ORDER BY completed_at_epoch_ms DESC, cleanup_audit_pk DESC LIMIT 100)`,
      )
      .run(projectName, projectName);
    store.database.exec("COMMIT;");
    return { ok: true, projectName, cleanupId, summary };
  } catch (error) {
    try {
      store.database.exec("ROLLBACK;");
    } catch {
      /* transaction may already be closed */
    }
    const message = error instanceof Error ? error.message : String(error);
    if (message.toLowerCase().includes("locked") || message.toLowerCase().includes("busy"))
      return failure(
        "state_store_retention_conflict",
        "another state-store maintenance operation is active",
        "retry_retention_cleanup",
      );
    return failure(
      "state_store_retention_failed",
      "retention cleanup failed closed",
      "retry_retention_cleanup",
    );
  } finally {
    store.close();
    await releaseRetentionLock(lock);
  }
}
