import { promises as fs } from "node:fs";
import path from "node:path";
import { openRunStateStore } from "./run_state_store";
import { cutoverMarkerPath, cutoverSentinelPath } from "./state_store_cutover_marker";
import type {
  RunStateCutover,
  RunStateCutoverResult,
  RunStateStoreFailure,
  RunStateStoreOpenResult,
} from "./model/run_state_store.model";

function failure(
  reasonCode: RunStateStoreFailure["reasonCode"],
  reason: string,
  nextAction: RunStateStoreFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): RunStateStoreFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function readCutover(store: RunStateStoreOpenResult): RunStateCutover | RunStateStoreFailure {
  if (!store.ok) return store;
  const row = store.database
    .prepare(
      `
      SELECT project_name, status, transition_revision, updated_at_epoch_ms,
             completed_at_epoch_ms, reason_code
      FROM state_store_cutover
      WHERE project_name = ?
    `,
    )
    .get(store.projectName);
  if (!row) {
    const now = Date.now();
    store.database
      .prepare(
        `
        INSERT INTO state_store_cutover (
          project_name, status, transition_revision, updated_at_epoch_ms
        ) VALUES (?, 'pre_cutover', 0, ?)
      `,
      )
      .run(store.projectName, now);
    return {
      projectName: store.projectName,
      status: "pre_cutover",
      transitionRevision: 0,
      updatedAtEpochMs: now,
    };
  }
  if (
    row.status !== "pre_cutover" &&
    row.status !== "cutover_in_progress" &&
    row.status !== "cutover_complete"
  )
    return failure(
      "state_store_cutover_failed",
      "run-state cutover state is invalid",
      "repair_state_store",
      { projectName: store.projectName, status: row.status },
    );
  return {
    projectName: store.projectName,
    status: row.status,
    transitionRevision: Number(row.transition_revision),
    updatedAtEpochMs: Number(row.updated_at_epoch_ms),
    ...(typeof row.completed_at_epoch_ms === "number"
      ? { completedAtEpochMs: row.completed_at_epoch_ms }
      : {}),
    ...(typeof row.reason_code === "string" ? { reasonCode: row.reason_code } : {}),
  };
}

async function legacySourceIsPending(
  store: RunStateStoreOpenResult,
  workspaceRootAbs: string,
): Promise<false | "required" | "not_ready"> {
  if (!store.ok) return false;
  const sourcePathRel = `.mcpjvm/${store.projectName}/correlation-index.json`;
  const sourcePathAbs = path.resolve(workspaceRootAbs, sourcePathRel);
  const sourceExists = await fs
    .stat(sourcePathAbs)
    .then(() => true)
    .catch(() => false);
  if (!sourceExists) return false;
  const provenance = store.database
    .prepare(
      `
      SELECT status
      FROM legacy_backfill_imports
      WHERE project_name = ? AND source_path_rel = ?
      ORDER BY legacy_backfill_import_pk DESC
      LIMIT 1
    `,
    )
    .get(store.projectName, sourcePathRel);
  if (!provenance) return "required";
  return provenance.status === "completed" ? false : "not_ready";
}

async function persistCutoverMarker(
  store: Extract<RunStateStoreOpenResult, { ok: true }>,
  workspaceRootAbs: string,
  status: "cutover_in_progress" | "cutover_complete",
): Promise<void> {
  const markerPath = cutoverMarkerPath(store.databasePathAbs);
  const sentinelPath = cutoverSentinelPath(workspaceRootAbs, store.projectName);
  const payload = `${JSON.stringify({ projectName: store.projectName, status, schemaVersion: store.schemaVersion }, null, 2)}\n`;
  await fs.mkdir(path.dirname(sentinelPath), { recursive: true });
  const sentinelTemporaryPath = `${sentinelPath}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(sentinelTemporaryPath, payload, "utf8");
  await fs.rename(sentinelTemporaryPath, sentinelPath);
  if (status === "cutover_in_progress") return;
  const markerTemporaryPath = `${markerPath}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(markerTemporaryPath, payload, "utf8");
  await fs.rename(markerTemporaryPath, markerPath);
}

export async function cutoverRunStateStore(args: {
  workspaceRootAbs: string;
  projectName: string;
}): Promise<RunStateCutoverResult> {
  const store = await openRunStateStore(args);
  if (!store.ok) return store;
  try {
    const current = readCutover(store);
    if ("ok" in current) return current;
    if (current.status === "cutover_complete") {
      const markerExists = await fs
        .stat(cutoverMarkerPath(store.databasePathAbs))
        .then(() => true)
        .catch(() => false);
      if (!markerExists) {
        try {
          await persistCutoverMarker(store, args.workspaceRootAbs, "cutover_complete");
        } catch (error) {
          return failure(
            "state_store_cutover_failed",
            "completed SQLite cutover is missing its durable marker",
            "retry_cutover",
            { error: error instanceof Error ? error.message : String(error) },
          );
        }
      }
      return { ok: true, cutover: current, idempotent: true };
    }
    if (current.status === "cutover_in_progress")
      return failure(
        "state_store_cutover_conflict",
        "another SQLite cutover transition is already in progress",
        "retry_cutover",
        { projectName: store.projectName, transitionRevision: current.transitionRevision },
      );
    const legacyReadiness = await legacySourceIsPending(store, args.workspaceRootAbs);
    if (legacyReadiness === "required")
      return failure(
        "legacy_backfill_required",
        "legacy correlation-index.json must be explicitly backfilled before cutover",
        "run_legacy_backfill",
        { sourcePathRel: `.mcpjvm/${store.projectName}/correlation-index.json` },
      );
    if (legacyReadiness === "not_ready")
      return failure(
        "state_store_cutover_not_ready",
        "legacy backfill provenance is not complete",
        "run_legacy_backfill",
        { sourcePathRel: `.mcpjvm/${store.projectName}/correlation-index.json` },
      );
    const now = Date.now();
    try {
      await persistCutoverMarker(store, args.workspaceRootAbs, "cutover_in_progress");
    } catch (error) {
      return failure(
        "state_store_cutover_failed",
        "SQLite cutover could not establish its independent durability sentinel",
        "retry_cutover",
        { error: error instanceof Error ? error.message : String(error) },
      );
    }
    store.database.exec("BEGIN IMMEDIATE;");
    try {
      store.database
        .prepare(
          `
          UPDATE state_store_cutover SET
            status = 'cutover_in_progress',
            transition_revision = transition_revision + 1,
            updated_at_epoch_ms = ?,
            reason_code = NULL
          WHERE project_name = ? AND status = 'pre_cutover'
        `,
        )
        .run(now, store.projectName);
      const claimed = store.database
        .prepare("SELECT status FROM state_store_cutover WHERE project_name = ?")
        .get(store.projectName);
      if (claimed?.status !== "cutover_in_progress")
        throw new Error("state_store_cutover_conflict");
      store.database
        .prepare(
          `
          UPDATE state_store_cutover SET
            status = 'cutover_complete',
            updated_at_epoch_ms = ?,
            completed_at_epoch_ms = ?
          WHERE project_name = ? AND status = 'cutover_in_progress'
        `,
        )
        .run(now, now, store.projectName);
      store.database.exec("COMMIT;");
    } catch (error) {
      try {
        store.database.exec("ROLLBACK;");
      } catch {
        // The transaction may already have been rolled back by SQLite.
      }
      if (error instanceof Error && error.message === "state_store_cutover_conflict")
        return failure(
          "state_store_cutover_conflict",
          "another SQLite cutover transition completed first",
          "retry_cutover",
          { projectName: store.projectName },
        );
      return failure(
        "state_store_cutover_failed",
        "SQLite cutover transition failed",
        "retry_cutover",
        { error: error instanceof Error ? error.message : String(error) },
      );
    }
    const completed = readCutover(store);
    if ("ok" in completed) return completed;
    try {
      await persistCutoverMarker(store, args.workspaceRootAbs, "cutover_complete");
    } catch (error) {
      return failure(
        "state_store_cutover_failed",
        "SQLite cutover completed but its durable marker could not be written",
        "retry_cutover",
        { error: error instanceof Error ? error.message : String(error) },
      );
    }
    return { ok: true, cutover: completed };
  } finally {
    store.close();
  }
}
