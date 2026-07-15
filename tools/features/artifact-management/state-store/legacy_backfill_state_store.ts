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
const IMPORTER_VERSION = "mcpjvm-372-v1";

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

function invalidEntryFields(value: unknown, expectedRunPathPrefix: string): string[] {
  if (!isRecord(value)) return ["entry"];
  const invalid: string[] = [];
  const keyType = value.keyType;
  const window = value.window;
  if (typeof value.runId !== "string") invalid.push("runId");
  if (typeof value.planName !== "string") invalid.push("planName");
  if (
    typeof value.runPath !== "string" ||
    !safeRelativePath(value.runPath) ||
    !value.runPath.replaceAll("\\", "/").startsWith(expectedRunPathPrefix)
  )
    invalid.push("runPath");
  if (typeof value.generatedAtEpochMs !== "number" || !Number.isInteger(value.generatedAtEpochMs))
    invalid.push("generatedAtEpochMs");
  if (value.status !== "ok" && value.status !== "fail_closed") invalid.push("status");
  if (typeof value.reasonCode !== "string") invalid.push("reasonCode");
  if (!["traceId", "requestId", "messageId"].includes(String(keyType))) invalid.push("keyType");
  const recognizedNonReconstructible =
    value.status === "fail_closed" &&
    value.reasonCode === "correlation_key_extraction_failed" &&
    typeof value.correlationSessionId === "undefined" &&
    typeof value.keyValue === "undefined";
  if (typeof value.correlationSessionId !== "string" && !recognizedNonReconstructible)
    invalid.push("correlationSessionId");
  if (!isRecord(window)) {
    invalid.push("window");
  } else if (
    typeof window.maxWindowMs !== "number" ||
    !Number.isInteger(window.maxWindowMs) ||
    window.maxWindowMs <= 0
  ) {
    invalid.push("window.maxWindowMs");
  }
  if (!Array.isArray(value.probeIds)) invalid.push("probeIds");
  else if (value.probeIds.some((probeId) => typeof probeId !== "string"))
    invalid.push("probeIds[]");
  if (typeof value.keyValue !== "undefined" && typeof value.keyValue !== "string")
    invalid.push("keyValue");
  return invalid;
}

function asEntry(value: unknown, expectedRunPathPrefix: string): LegacyBackfillEntry | undefined {
  const invalid = invalidEntryFields(value, expectedRunPathPrefix);
  if (invalid.length > 0 || !isRecord(value)) return undefined;
  const record = value as Record<string, any>;
  const nonReconstructible =
    record.status === "fail_closed" &&
    record.reasonCode === "correlation_key_extraction_failed" &&
    typeof record.correlationSessionId === "undefined" &&
    typeof record.keyValue === "undefined";
  const keyType = record.keyType;
  const window = record.window as Record<string, any>;
  return {
    runId: record.runId,
    planName: record.planName,
    runPath: record.runPath.replaceAll("\\", "/"),
    generatedAtEpochMs: record.generatedAtEpochMs,
    status: record.status,
    reasonCode: record.reasonCode,
    keyType: keyType as LegacyBackfillEntry["keyType"],
    ...(typeof record.keyValue === "string" ? { keyValue: record.keyValue } : {}),
    ...(typeof record.correlationSessionId === "string"
      ? { correlationSessionId: record.correlationSessionId }
      : {}),
    window: {
      ...(typeof window.startEpochMs === "number" ? { startEpochMs: window.startEpochMs } : {}),
      ...(typeof window.endEpochMs === "number" ? { endEpochMs: window.endEpochMs } : {}),
      maxWindowMs: window.maxWindowMs,
    },
    probeIds: record.probeIds,
    ...(nonReconstructible
      ? { nonReconstructible: true, missingFields: ["correlationSessionId", "keyValue"] }
      : {}),
  };
}

function emptySummary(
  sourcePathRel: string,
  sourceChecksum: string,
  version: number,
): LegacyBackfillSummary {
  return {
    scannedEntries: 0,
    insertedEntries: 0,
    skippedEntries: 0,
    conflictingEntries: 0,
    invalidEntries: 0,
    nonReconstructibleEntries: 0,
    sourcePathRel,
    sourceChecksum,
    detectedLegacySchemaVersion: version,
    backfillStatus: "completed",
    nextAction: "none",
  };
}

function readPersistedBackfillReasons(
  store: OpenRunStateStore,
  importPk: number | undefined,
): Array<Record<string, unknown>> {
  if (typeof importPk !== "number") return [];
  return store.database
    .prepare(
      `SELECT entry_index, plan_name, run_id, reason_code,
              missing_fields_json, violated_fields_json, conflicting_fields_json
         FROM legacy_backfill_audits
        WHERE legacy_backfill_import_pk = ?
        ORDER BY entry_index ASC
        LIMIT ${MAX_REASONS}`,
    )
    .all(importPk)
    .map((row) => ({
      entryIndex: Number(row.entry_index),
      ...(typeof row.plan_name === "string" ? { planName: row.plan_name } : {}),
      ...(typeof row.run_id === "string" ? { runId: row.run_id } : {}),
      reasonCode: row.reason_code,
      ...(typeof row.missing_fields_json === "string"
        ? { missingFields: JSON.parse(row.missing_fields_json) }
        : {}),
      ...(typeof row.violated_fields_json === "string"
        ? { violatedFields: JSON.parse(row.violated_fields_json) }
        : {}),
      ...(typeof row.conflicting_fields_json === "string"
        ? { conflictingFields: JSON.parse(row.conflicting_fields_json) }
        : {}),
    }));
}

function auditBackfillReason(args: {
  store: OpenRunStateStore;
  importPk: number;
  entryIndex: number;
  entry: LegacyBackfillEntry;
  reasonCode: string;
  missingFields?: string[];
  violatedFields?: string[];
  conflictingFields?: string[];
}): void {
  args.store.database
    .prepare(
      `INSERT INTO legacy_backfill_audits (
         legacy_backfill_import_pk, entry_index, plan_name, run_id, reason_code,
         missing_fields_json, violated_fields_json, conflicting_fields_json, created_at_epoch_ms
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .run(
      args.importPk,
      args.entryIndex,
      args.entry.planName,
      args.entry.runId,
      args.reasonCode,
      args.missingFields ? JSON.stringify(args.missingFields) : null,
      args.violatedFields ? JSON.stringify(args.violatedFields) : null,
      args.conflictingFields ? JSON.stringify(args.conflictingFields) : null,
      Date.now(),
    );
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
  let sourceBytes: Buffer;
  try {
    sourceBytes = await fs.readFile(sourcePathAbs);
  } catch {
    return failure(
      "legacy_backfill_source_missing",
      "legacy correlation index was not found",
      "correct_legacy_source",
      { sourcePathRel },
    );
  }
  const sourceChecksum = createHash("sha256").update(sourceBytes).digest("hex");
  let parsed: unknown;
  try {
    parsed = JSON.parse(sourceBytes.toString("utf8"));
  } catch {
    return failure(
      "legacy_backfill_source_invalid",
      "legacy correlation index is not valid JSON",
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
  for (const [entryIndex, rawEntry] of parsed.entries.entries()) {
    const entry = asEntry(rawEntry, expectedRunPathPrefix);
    if (!entry) {
      const rawRecord = isRecord(rawEntry) ? rawEntry : undefined;
      return failure(
        "legacy_backfill_source_invalid",
        `legacy correlation index contains an invalid entry at index ${entryIndex}`,
        "correct_legacy_source",
        {
          sourcePathRel,
          entryIndex,
          ...(typeof rawRecord?.runId === "string" ? { runId: rawRecord.runId } : {}),
          ...(typeof rawRecord?.planName === "string" ? { planName: rawRecord.planName } : {}),
          violatedFields: invalidEntryFields(rawEntry, expectedRunPathPrefix),
          // Preserve the pre-#441 field for existing callers while exposing the
          // reconciliation contract's canonical diagnostic name.
          invalidFields: invalidEntryFields(rawEntry, expectedRunPathPrefix),
        },
      );
    }
    normalizedEntries.push(entry);
  }
  const store = await openRunStateStore({ workspaceRootAbs: args.workspaceRootAbs, projectName });
  if (!store.ok)
    return failure("legacy_backfill_failed", store.reason, "retry_state_store", store.reasonMeta);
  const summary = emptySummary(sourcePathRel, sourceChecksum, 1);
  summary.scannedEntries = parsed.entries.length;
  const reasons: Array<Record<string, unknown>> = [];
  const reasonCount = { value: 0 };
  try {
    const cutover = store.database
      .prepare("SELECT status FROM state_store_cutover WHERE project_name = ?")
      .get(projectName);
    if (cutover?.status === "cutover_complete")
      return failure(
        "legacy_write_disabled",
        "legacy correlation-index backfill is disabled after SQLite cutover",
        "use_sqlite_state_store",
        { sourcePathRel },
      );
    const prior = store.database
      .prepare(
        `
      SELECT legacy_backfill_import_pk, status, inserted_count, skipped_count, conflicting_count, invalid_count,
             non_reconstructible_count, source_checksum
      FROM legacy_backfill_imports
      WHERE project_name = ? AND source_path_rel = ?
    `,
      )
      .get(projectName, sourcePathRel);
    if (prior?.status === "completed") {
      if (prior.source_checksum !== sourceChecksum)
        return failure(
          "legacy_backfill_checksum_changed",
          "legacy correlation index changed after the completed backfill",
          "correct_legacy_source",
          { sourcePathRel },
        );
      return {
        ok: true,
        summary: {
          ...summary,
          insertedEntries: Number(prior.inserted_count ?? 0),
          skippedEntries: Number(prior.skipped_count ?? 0),
          conflictingEntries: Number(prior.conflicting_count ?? 0),
          invalidEntries: Number(prior.invalid_count ?? 0),
          nonReconstructibleEntries: Number(prior.non_reconstructible_count ?? 0),
          sourceChecksum,
          backfillStatus: "noop",
          ...(readPersistedBackfillReasons(store, Number(prior.legacy_backfill_import_pk)).length >
          0
            ? {
                reasons: readPersistedBackfillReasons(
                  store,
                  Number(prior.legacy_backfill_import_pk),
                ),
              }
            : {}),
        },
      };
    }
    const sourceIdentities = new Set<string>();
    for (const entry of normalizedEntries) {
      const identity = `${entry.planName}\u0000${entry.runId}`;
      if (sourceIdentities.has(identity))
        return failure(
          "legacy_backfill_conflict",
          "legacy backfill contains duplicate plan run identities",
          "correct_legacy_source",
          { planName: entry.planName, runId: entry.runId },
        );
      sourceIdentities.add(identity);
    }
    store.database.exec("BEGIN IMMEDIATE;");
    try {
      let importPk: number;
      if (prior?.status === "rejected") {
        importPk = Number(prior.legacy_backfill_import_pk);
        store.database
          .prepare(`DELETE FROM legacy_backfill_audits WHERE legacy_backfill_import_pk = ?`)
          .run(importPk);
        store.database
          .prepare(
            `UPDATE legacy_backfill_imports
                SET detected_legacy_schema_version = 1,
                    importer_version = ?, import_started_at_epoch_ms = ?,
                    import_completed_at_epoch_ms = NULL, inserted_count = 0,
                    skipped_count = 0, conflicting_count = 0, invalid_count = 0,
                    non_reconstructible_count = 0, source_checksum = ?,
                    status = 'in_progress', reason_code = NULL
              WHERE legacy_backfill_import_pk = ?`,
          )
          .run(IMPORTER_VERSION, Date.now(), sourceChecksum, importPk);
      } else {
        store.database
          .prepare(
            `
          INSERT INTO legacy_backfill_imports (
            project_name, source_path_rel, detected_legacy_schema_version,
            importer_version, import_started_at_epoch_ms, source_checksum, status
          ) VALUES (?, ?, 1, ?, ?, ?, 'in_progress')
        `,
          )
          .run(projectName, sourcePathRel, IMPORTER_VERSION, Date.now(), sourceChecksum);
        importPk = Number(
          store.database.prepare("SELECT last_insert_rowid() AS import_pk").get()?.import_pk,
        );
      }
      if (!Number.isInteger(importPk) || importPk <= 0)
        throw new Error("legacy_backfill_import_missing");
      for (const [entryIndex, entry] of normalizedEntries.entries()) {
        if (!entry) {
          summary.invalidEntries += 1;
          reasonCount.value += 1;
          if (reasons.length < MAX_REASONS)
            reasons.push({ entryIndex, reasonCode: "legacy_entry_invalid" });
          continue;
        }
        if (entry.nonReconstructible) {
          summary.skippedEntries += 1;
          summary.nonReconstructibleEntries += 1;
          reasonCount.value += 1;
          auditBackfillReason({
            store,
            importPk,
            entryIndex,
            entry,
            reasonCode: "terminal_correlation_not_reconstructible",
            ...(entry.missingFields ? { missingFields: entry.missingFields } : {}),
          });
          if (reasons.length < MAX_REASONS)
            reasons.push({
              entryIndex,
              planName: entry.planName,
              runId: entry.runId,
              reasonCode: "terminal_correlation_not_reconstructible",
              missingFields: entry.missingFields,
            });
          continue;
        }
        if (!entry.correlationSessionId) {
          summary.invalidEntries += 1;
          reasonCount.value += 1;
          auditBackfillReason({
            store,
            importPk,
            entryIndex,
            entry,
            reasonCode: "legacy_entry_invalid",
            violatedFields: ["correlationSessionId"],
          });
          if (reasons.length < MAX_REASONS)
            reasons.push({
              entryIndex,
              planName: entry.planName,
              runId: entry.runId,
              reasonCode: "legacy_entry_invalid",
              violatedFields: ["correlationSessionId"],
            });
          continue;
        }
        const correlationPathRel = safeRelativePath(`${entry.runPath}/correlation/correlation.json`)
          ? `${entry.runPath}/correlation/correlation.json`
          : null;
        const plan = store.database
          .prepare(
            `SELECT plan_run_pk, status, run_dir_path_rel FROM plan_runs
             WHERE project_name = ? AND plan_name = ? AND run_id = ?`,
          )
          .get(projectName, entry.planName, entry.runId);
        if (!plan) {
          store.database
            .prepare(
              `INSERT INTO plan_runs (project_name, plan_name, run_id, status, run_dir_path_rel)
               VALUES (?, ?, ?, 'executed', ?)`,
            )
            .run(projectName, entry.planName, entry.runId, entry.runPath);
        } else if (plan.run_dir_path_rel !== entry.runPath) {
          summary.conflictingEntries += 1;
          reasonCount.value += 1;
          const conflictingFields = ["runPath"];
          auditBackfillReason({
            store,
            importPk,
            entryIndex,
            entry,
            reasonCode: "legacy_canonical_divergence",
            conflictingFields,
          });
          if (reasons.length < MAX_REASONS)
            reasons.push({
              entryIndex,
              planName: entry.planName,
              runId: entry.runId,
              reasonCode: "legacy_canonical_divergence",
              conflictingFields,
            });
          continue;
        }
        const resolvedPlan = store.database
          .prepare(
            `SELECT plan_run_pk FROM plan_runs
             WHERE project_name = ? AND plan_name = ? AND run_id = ?`,
          )
          .get(projectName, entry.planName, entry.runId);
        if (typeof resolvedPlan?.plan_run_pk !== "number")
          throw new Error("legacy_backfill_plan_run_missing");
        const existingCorrelation = store.database
          .prepare(
            `SELECT correlation_run_pk, status, reason_code, window_start_epoch_ms,
                    window_end_epoch_ms, max_window_ms, correlation_path_rel
               FROM correlation_runs
              WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?`,
          )
          .get(projectName, entry.runId, entry.correlationSessionId);
        const expectedCorrelationStatus = entry.status === "ok" ? "correlated" : "fail_closed";
        if (existingCorrelation) {
          const conflictingFields: string[] = [];
          if (existingCorrelation.status !== expectedCorrelationStatus)
            conflictingFields.push("status");
          if (existingCorrelation.reason_code !== entry.reasonCode)
            conflictingFields.push("reasonCode");
          if (Number(existingCorrelation.max_window_ms) !== entry.window.maxWindowMs)
            conflictingFields.push("window.maxWindowMs");
          if (
            (existingCorrelation.window_start_epoch_ms ?? null) !==
            (entry.window.startEpochMs ?? null)
          )
            conflictingFields.push("window.startEpochMs");
          if (
            (existingCorrelation.window_end_epoch_ms ?? null) !== (entry.window.endEpochMs ?? null)
          )
            conflictingFields.push("window.endEpochMs");
          const existingKey = store.database
            .prepare(
              `SELECT key_type, key_value_hash
                 FROM correlation_keys
                WHERE correlation_run_pk = ?`,
            )
            .get(existingCorrelation.correlation_run_pk);
          const expectedKeyHash = entry.keyValue
            ? createHash("sha256").update(entry.keyValue).digest("hex")
            : undefined;
          if (expectedKeyHash && !existingKey) {
            conflictingFields.push("keyValue");
          } else if (!expectedKeyHash && existingKey) {
            conflictingFields.push("keyValue");
          } else if (expectedKeyHash && existingKey) {
            if (existingKey.key_type !== entry.keyType) conflictingFields.push("keyType");
            if (existingKey.key_value_hash !== expectedKeyHash) conflictingFields.push("keyValue");
          }
          if (conflictingFields.length > 0) {
            summary.conflictingEntries += 1;
            reasonCount.value += 1;
            auditBackfillReason({
              store,
              importPk,
              entryIndex,
              entry,
              reasonCode: "legacy_canonical_divergence",
              conflictingFields,
            });
            if (reasons.length < MAX_REASONS)
              reasons.push({
                entryIndex,
                planName: entry.planName,
                runId: entry.runId,
                reasonCode: "legacy_canonical_divergence",
                conflictingFields,
              });
            continue;
          }
          summary.skippedEntries += 1;
          auditBackfillReason({
            store,
            importPk,
            entryIndex,
            entry,
            reasonCode: "legacy_correlation_idempotent_skip",
          });
          reasonCount.value += 1;
          if (reasons.length < MAX_REASONS)
            reasons.push({
              entryIndex,
              planName: entry.planName,
              runId: entry.runId,
              reasonCode: "legacy_correlation_idempotent_skip",
            });
          continue;
        }
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
            expectedCorrelationStatus,
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
        if (entry.probeIds.length > 0) {
          summary.nonReconstructibleEntries += 1;
          reasonCount.value += 1;
          auditBackfillReason({
            store,
            importPk,
            entryIndex,
            entry,
            reasonCode: "probe_observations_not_reconstructed",
          });
          if (reasons.length < MAX_REASONS)
            reasons.push({
              runId: entry.runId,
              reasonCode: "probe_observations_not_reconstructed",
            });
        }
      }
      store.database
        .prepare(
          `
        UPDATE legacy_backfill_imports SET
          import_completed_at_epoch_ms = ?, inserted_count = ?, skipped_count = ?,
          conflicting_count = ?, invalid_count = ?, non_reconstructible_count = ?,
          source_checksum = ?, status = ?, reason_code = ?
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
          sourceChecksum,
          summary.conflictingEntries > 0 ? "rejected" : "completed",
          summary.conflictingEntries > 0
            ? "legacy_canonical_divergence"
            : reasons.length
              ? "legacy_entries_partial"
              : null,
          projectName,
          sourcePathRel,
        );
      store.database.exec("COMMIT;");
      if (summary.conflictingEntries > 0) {
        const divergence = reasons.find(
          (reason) => reason.reasonCode === "legacy_canonical_divergence",
        );
        return failure(
          "legacy_backfill_conflict",
          "legacy correlation data diverges from canonical SQLite state",
          "correct_legacy_source",
          divergence,
        );
      }
      return {
        ok: true,
        summary: {
          ...summary,
          ...(reasons.length ? { reasons } : {}),
          ...(reasonCount.value > reasons.length ? { reasonsTruncated: true } : {}),
        },
      };
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
