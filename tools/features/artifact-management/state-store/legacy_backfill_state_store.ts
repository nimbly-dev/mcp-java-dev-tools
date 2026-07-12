import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { openRunStateStore } from "./run_state_store";
import type {
  LegacyBackfillFailure,
  LegacyBackfillEntry,
  LegacyBackfillRequest,
  LegacyBackfillResult,
  LegacyBackfillSummary,
  OpenRunStateStore,
} from "./model/run_state_store.model";

const MAX_ENTRIES = 10_000;
const MAX_REASONS = 100;
const IMPORTER_VERSION = "mcpjvm-367-v1";

function failure(
  reasonCode: LegacyBackfillFailure["reasonCode"],
  reason: string,
  nextAction: LegacyBackfillFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): LegacyBackfillFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function safeRelativePath(value: string): boolean {
  return (
    value.length > 0 &&
    !path.isAbsolute(value) &&
    !path.win32.isAbsolute(value) &&
    !value.split(/[\\/]/).includes("..")
  );
}

function asEntry(value: unknown, expectedRunPathPrefix: string): LegacyBackfillEntry | undefined {
  if (!isRecord(value)) return undefined;
  const keyType = value.keyType;
  const window = value.window;
  if (
    typeof value.runId !== "string" ||
    typeof value.planName !== "string" ||
    typeof value.runPath !== "string" ||
    !safeRelativePath(value.runPath) ||
    !value.runPath.replaceAll("\\", "/").startsWith(expectedRunPathPrefix) ||
    typeof value.generatedAtEpochMs !== "number" ||
    !Number.isInteger(value.generatedAtEpochMs) ||
    (value.status !== "ok" && value.status !== "fail_closed") ||
    typeof value.reasonCode !== "string" ||
    !["traceId", "requestId", "messageId"].includes(String(keyType)) ||
    typeof value.correlationSessionId !== "string" ||
    !isRecord(window) ||
    typeof window.maxWindowMs !== "number" ||
    !Number.isInteger(window.maxWindowMs) ||
    window.maxWindowMs <= 0 ||
    !Array.isArray(value.probeIds) ||
    value.probeIds.some((probeId) => typeof probeId !== "string")
  )
    return undefined;
  if (typeof value.keyValue !== "undefined" && typeof value.keyValue !== "string") return undefined;
  return {
    runId: value.runId,
    planName: value.planName,
    runPath: value.runPath.replaceAll("\\", "/"),
    generatedAtEpochMs: value.generatedAtEpochMs,
    status: value.status,
    reasonCode: value.reasonCode,
    keyType: keyType as LegacyBackfillEntry["keyType"],
    ...(typeof value.keyValue === "string" ? { keyValue: value.keyValue } : {}),
    correlationSessionId: value.correlationSessionId,
    window: {
      ...(typeof window.startEpochMs === "number" ? { startEpochMs: window.startEpochMs } : {}),
      ...(typeof window.endEpochMs === "number" ? { endEpochMs: window.endEpochMs } : {}),
      maxWindowMs: window.maxWindowMs,
    },
    probeIds: value.probeIds,
  };
}

function emptySummary(sourcePathRel: string, version: number): LegacyBackfillSummary {
  return {
    scannedEntries: 0,
    insertedEntries: 0,
    skippedEntries: 0,
    conflictingEntries: 0,
    invalidEntries: 0,
    nonReconstructibleEntries: 0,
    sourcePathRel,
    detectedLegacySchemaVersion: version,
    backfillStatus: "completed",
    nextAction: "none",
  };
}

function targetRowCount(store: OpenRunStateStore): number {
  const row = store.database
    .prepare(
      `
    SELECT
      (SELECT count(*) FROM artifacts) +
      (SELECT count(*) FROM suite_runs) +
      (SELECT count(*) FROM plan_runs) +
      (SELECT count(*) FROM correlation_runs) +
      (SELECT count(*) FROM correlation_keys) +
      (SELECT count(*) FROM correlation_line_expectations) +
      (SELECT count(*) FROM correlation_probe_observations) +
      (SELECT count(*) FROM watcher_runs) +
      (SELECT count(*) FROM external_verifications) AS total
  `,
    )
    .get();
  return typeof row?.total === "number" ? row.total : 0;
}

/** Imports only the supported v1 correlation index; legacy files remain untouched. */
export async function backfillLegacyCorrelationIndex(
  args: LegacyBackfillRequest,
): Promise<LegacyBackfillResult> {
  const projectName = args.projectName.trim();
  const sourcePathRel = `.mcpjvm/${projectName}/correlation-index.json`;
  if (!projectName || projectName === "." || projectName === ".." || /[\\/]/.test(projectName))
    return failure(
      "legacy_backfill_source_invalid",
      "projectName must be a non-empty path segment",
      "correct_legacy_source",
    );
  const sourcePathAbs = path.join(path.resolve(args.workspaceRootAbs), sourcePathRel);
  let parsed: unknown;
  try {
    parsed = JSON.parse(await fs.readFile(sourcePathAbs, "utf8"));
  } catch {
    return failure(
      "legacy_backfill_source_missing",
      "legacy correlation index was not found",
      "correct_legacy_source",
      { sourcePathRel },
    );
  }
  if (!isRecord(parsed) || parsed.version !== 1 || !Array.isArray(parsed.entries))
    return failure(
      "legacy_backfill_schema_unsupported",
      "legacy correlation index schema is unsupported",
      "correct_legacy_source",
      { sourcePathRel },
    );
  if (parsed.entries.length > MAX_ENTRIES)
    return failure(
      "legacy_backfill_source_invalid",
      "legacy correlation index exceeds the bounded entry limit",
      "correct_legacy_source",
      { maxEntries: MAX_ENTRIES },
    );
  const expectedRunPathPrefix = `.mcpjvm/${projectName}/plans/regression/`;
  const normalizedEntries: LegacyBackfillEntry[] = [];
  for (const rawEntry of parsed.entries) {
    const entry = asEntry(rawEntry, expectedRunPathPrefix);
    if (!entry)
      return failure(
        "legacy_backfill_source_invalid",
        "legacy correlation index contains an invalid entry",
        "correct_legacy_source",
        { sourcePathRel },
      );
    normalizedEntries.push(entry);
  }
  const store = await openRunStateStore({ workspaceRootAbs: args.workspaceRootAbs, projectName });
  if (!store.ok)
    return failure("legacy_backfill_failed", store.reason, "retry_state_store", store.reasonMeta);
  const summary = emptySummary(sourcePathRel, 1);
  summary.scannedEntries = parsed.entries.length;
  const reasons: Array<Record<string, unknown>> = [];
  try {
    const prior = store.database
      .prepare(
        `
      SELECT status, inserted_count, skipped_count, conflicting_count, invalid_count,
             non_reconstructible_count
      FROM legacy_backfill_imports
      WHERE project_name = ? AND source_path_rel = ?
    `,
      )
      .get(projectName, sourcePathRel);
    if (prior?.status === "completed") {
      return {
        ok: true,
        summary: {
          ...summary,
          insertedEntries: Number(prior.inserted_count ?? 0),
          skippedEntries: Number(prior.skipped_count ?? 0),
          conflictingEntries: Number(prior.conflicting_count ?? 0),
          invalidEntries: Number(prior.invalid_count ?? 0),
          nonReconstructibleEntries: Number(prior.non_reconstructible_count ?? 0),
          backfillStatus: "noop",
        },
      };
    }
    if (targetRowCount(store) > 0)
      return failure(
        "legacy_backfill_target_not_empty",
        "legacy backfill requires an empty SQLite projection",
        "run_state_store_rebuild",
        { sourcePathRel },
      );
    store.database.exec("BEGIN IMMEDIATE;");
    try {
      store.database
        .prepare(
          `
        INSERT INTO legacy_backfill_imports (
          project_name, source_path_rel, detected_legacy_schema_version,
          importer_version, import_started_at_epoch_ms, status
        ) VALUES (?, ?, 1, ?, ?, 'in_progress')
      `,
        )
        .run(projectName, sourcePathRel, IMPORTER_VERSION, Date.now());
      for (const entry of normalizedEntries) {
        if (!entry) {
          summary.invalidEntries += 1;
          if (reasons.length < MAX_REASONS) reasons.push({ reasonCode: "legacy_entry_invalid" });
          continue;
        }
        if (!entry.correlationSessionId.trim()) {
          summary.nonReconstructibleEntries += 1;
          if (reasons.length < MAX_REASONS)
            reasons.push({ runId: entry.runId, reasonCode: "correlation_session_missing" });
          continue;
        }
        const correlationPathRel = safeRelativePath(`${entry.runPath}/correlation/correlation.json`)
          ? `${entry.runPath}/correlation/correlation.json`
          : null;
        store.database
          .prepare(
            `
          INSERT INTO plan_runs (project_name, plan_name, run_id, status, run_dir_path_rel)
          VALUES (?, ?, ?, 'executed', ?)
        `,
          )
          .run(projectName, entry.planName, entry.runId, entry.runPath);
        const plan = store.database
          .prepare(
            `
          SELECT plan_run_pk FROM plan_runs
          WHERE project_name = ? AND plan_name = ? AND run_id = ?
        `,
          )
          .get(projectName, entry.planName, entry.runId);
        if (typeof plan?.plan_run_pk !== "number")
          throw new Error("legacy_backfill_plan_run_missing");
        store.database
          .prepare(
            `
          INSERT INTO correlation_runs (
            plan_name, project_name, run_id, correlation_session_id, status,
            reason_code, expected_line_count, matched_line_count,
            window_start_epoch_ms, window_end_epoch_ms, max_window_ms,
            started_at_epoch_ms, correlation_path_rel
          ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?)
        `,
          )
          .run(
            entry.planName,
            projectName,
            entry.runId,
            entry.correlationSessionId,
            entry.status === "ok" ? "correlated" : "fail_closed",
            entry.reasonCode,
            entry.window.startEpochMs ?? null,
            entry.window.endEpochMs ?? null,
            entry.window.maxWindowMs,
            entry.generatedAtEpochMs,
            correlationPathRel,
          );
        const correlation = store.database
          .prepare(
            `
          SELECT correlation_run_pk FROM correlation_runs
          WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?
        `,
          )
          .get(projectName, entry.runId, entry.correlationSessionId);
        if (typeof correlation?.correlation_run_pk !== "number")
          throw new Error("legacy_backfill_correlation_missing");
        if (entry.keyValue)
          store.database
            .prepare(
              `
          INSERT INTO correlation_keys (correlation_run_pk, key_type, key_value_sanitized, key_value_hash)
          VALUES (?, ?, NULL, ?)
        `,
            )
            .run(
              correlation.correlation_run_pk,
              entry.keyType,
              createHash("sha256").update(entry.keyValue).digest("hex"),
            );
        summary.insertedEntries += 1;
        summary.nonReconstructibleEntries += entry.probeIds.length > 0 ? 1 : 0;
      }
      store.database
        .prepare(
          `
        UPDATE legacy_backfill_imports SET
          import_completed_at_epoch_ms = ?, inserted_count = ?, skipped_count = ?,
          conflicting_count = ?, invalid_count = ?, non_reconstructible_count = ?,
          status = 'completed', reason_code = ?
        WHERE project_name = ? AND source_path_rel = ?
      `,
        )
        .run(
          Date.now(),
          summary.insertedEntries,
          summary.skippedEntries,
          summary.conflictingEntries,
          summary.invalidEntries,
          summary.nonReconstructibleEntries,
          reasons.length ? "legacy_entries_partial" : null,
          projectName,
          sourcePathRel,
        );
      store.database.exec("COMMIT;");
      return { ok: true, summary: reasons.length ? { ...summary, reasons } : summary };
    } catch (error) {
      try {
        store.database.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      return failure(
        "legacy_backfill_failed",
        "legacy correlation index backfill failed",
        "retry_state_store",
        { error: error instanceof Error ? error.message : String(error) },
      );
    }
  } finally {
    store.close();
  }
}
