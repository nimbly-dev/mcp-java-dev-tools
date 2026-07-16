import path from "node:path";
import type {
  ExternalVerificationPersistenceFailure,
  ExternalVerificationPersistenceResult,
  ExternalVerificationProjection,
  OpenRunStateStore,
} from "./model/run_state_store.model";

const MAX_SUMMARY_BYTES = 8_192;

function isSafeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !path.isAbsolute(pathRel) &&
    !path.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}

function failure(
  reasonCode: ExternalVerificationPersistenceFailure["reasonCode"],
  reason: string,
  nextAction: ExternalVerificationPersistenceFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): ExternalVerificationPersistenceFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function boundedJson(value: unknown): string | null {
  if (typeof value === "undefined") return null;
  const seen = new WeakSet<object>();
  const sanitized = JSON.parse(
    JSON.stringify(value, (key, entry: unknown) => {
      if (
        /authorization|credential|password|secret|token|cookie|rawbody|responsebody|(^|_)body($|_)/i.test(
          key,
        )
      ) {
        return "[REDACTED]";
      }
      if (typeof entry === "object" && entry !== null) {
        if (seen.has(entry)) return "[CIRCULAR]";
        seen.add(entry);
      }
      return entry;
    }),
  ) as unknown;
  const serialized = JSON.stringify(sanitized);
  return serialized.length <= MAX_SUMMARY_BYTES ? serialized : JSON.stringify({ truncated: true });
}

function boundedText(value: unknown): string | null {
  const serialized = boundedJson(value);
  if (serialized === null) return null;
  return serialized.length <= MAX_SUMMARY_BYTES ? serialized : JSON.stringify({ truncated: true });
}

function isTerminalStatus(status: string): boolean {
  return status === "pass" || status === "fail_assertion" || status === "blocked_runtime";
}

function assertionRows(projection: ExternalVerificationProjection): Array<{
  id: string;
  actualPath: string;
  operator: string;
  status: "pass" | "fail" | "blocked" | "skipped_optional";
  expectedSummaryText: string | null;
  actualSummaryText: string | null;
  reasonCode: string | null;
}> {
  return (projection.assertions ?? []).map((assertion) => ({
    id: assertion.id,
    actualPath: assertion.actualPath,
    operator: assertion.operator,
    status: assertion.status,
    expectedSummaryText: boundedText(assertion.expected),
    actualSummaryText: boundedText(assertion.actual),
    reasonCode: assertion.reasonCode ?? null,
  }));
}

/** Persists one bounded provider-neutral external-verification summary. */
export function upsertExternalVerificationSummary(args: {
  store: OpenRunStateStore;
  projectName: string;
  projection: ExternalVerificationProjection;
}): ExternalVerificationPersistenceResult {
  const p = args.projection;
  const planName = p.planName.trim();
  const runId = p.runId.trim();
  const verificationName = p.verificationName.trim();
  const assertions = assertionRows(p);
  if (
    args.projectName !== args.store.projectName ||
    !planName ||
    !runId ||
    !verificationName ||
    !Number.isInteger(p.verificationOrder) ||
    p.verificationOrder < 0 ||
    (p.providerType !== "http" && p.providerType !== "sql") ||
    !isTerminalStatus(p.status) ||
    (p.durationMs !== undefined && (!Number.isInteger(p.durationMs) || p.durationMs < 0)) ||
    !Number.isInteger(p.createdAtEpochMs) ||
    !Number.isInteger(p.updatedAtEpochMs) ||
    p.updatedAtEpochMs < p.createdAtEpochMs ||
    (p.artifactPathRel !== undefined && !isSafeRelativePath(p.artifactPathRel)) ||
    assertions.some(
      (assertion) =>
        !assertion.id.trim() || !assertion.actualPath.trim() || !assertion.operator.trim(),
    )
  ) {
    return failure(
      "external_verification_state_invalid",
      "External-verification identity, provider, status, summaries, and Artifact path are invalid",
      "correct_external_verification_input",
      { planName, runId, verificationName, providerType: p.providerType },
    );
  }

  let requestSummaryJson: string | null;
  let responseSummaryJson: string | null;
  try {
    requestSummaryJson = boundedJson(p.requestSummary);
    responseSummaryJson = boundedJson(p.responseSummary);
  } catch (error) {
    return failure(
      "external_verification_state_redaction_failed",
      "External-verification summary could not be sanitized",
      "correct_external_verification_input",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }

  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      db.prepare(
        `INSERT INTO plan_runs (
          project_name,
          plan_name,
          run_id,
          status,
          run_dir_path_rel
        )
        VALUES (?, ?, ?, 'executed', ?)
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
      if (typeof plan?.plan_run_pk !== "number")
        throw new Error("external_verification_plan_run_missing");

      const existing = db
        .prepare(
          `SELECT external_verification_pk, verification_order, provider_type,
                  status, reason_code, duration_ms, connection_ref,
                  request_summary_json, response_summary_json, revision
           FROM external_verifications
           WHERE project_name = ? AND plan_name = ? AND run_id = ? AND verification_name = ?`,
        )
        .get(args.projectName, planName, runId, verificationName);
      const currentRevision = typeof existing?.revision === "number" ? existing.revision : 0;
      if (typeof p.revision === "number" && existing && p.revision !== currentRevision) {
        db.exec("ROLLBACK;");
        return failure(
          "external_verification_state_stale_revision",
          "External-verification state revision is stale",
          "resume_same_suite",
          { expectedRevision: p.revision, currentRevision, verificationName },
        );
      }

      if (existing && typeof existing.external_verification_pk === "number") {
        const sameParent =
          existing.verification_order === p.verificationOrder &&
          existing.provider_type === p.providerType &&
          existing.status === p.status &&
          (existing.reason_code ?? null) === (p.reasonCode ?? null) &&
          (existing.duration_ms ?? null) === (p.durationMs ?? null) &&
          (existing.connection_ref ?? null) === (p.connectionRef ?? null) &&
          (existing.request_summary_json ?? null) === requestSummaryJson &&
          (existing.response_summary_json ?? null) === responseSummaryJson;
        const persistedAssertions = db
          .prepare(
            `SELECT assertion_id, actual_path, operator, status,
                    expected_summary_text, actual_summary_text, reason_code
             FROM external_verification_assertions
             WHERE external_verification_pk = ?
             ORDER BY assertion_id ASC`,
          )
          .all(existing.external_verification_pk);
        const sameAssertions =
          JSON.stringify(persistedAssertions) ===
          JSON.stringify(
            assertions
              .map((assertion) => ({
                assertion_id: assertion.id,
                actual_path: assertion.actualPath,
                operator: assertion.operator,
                status: assertion.status,
                expected_summary_text: assertion.expectedSummaryText,
                actual_summary_text: assertion.actualSummaryText,
                reason_code: assertion.reasonCode,
              }))
              .sort((lhs, rhs) => lhs.assertion_id.localeCompare(rhs.assertion_id)),
          );
        if (isTerminalStatus(String(existing.status))) {
          db.exec("ROLLBACK;");
          return sameParent && sameAssertions
            ? { ok: true, revision: currentRevision }
            : failure(
                "external_verification_state_conflict",
                "Terminal external-verification state is immutable",
                "resume_same_suite",
                { verificationName, currentRevision },
              );
        }
      }

      const nextRevision = currentRevision + 1;
      const createdAt = p.createdAtEpochMs;
      db.prepare(
        `INSERT INTO external_verifications (
          plan_run_pk,
          project_name,
          plan_name,
          run_id,
          suite_run_id,
          verification_name,
          verification_order,
          provider_type,
          status,
          reason_code,
          duration_ms,
          connection_ref,
          request_summary_json,
          response_summary_json,
          assertion_pass_count,
          assertion_fail_count,
          assertion_blocked_count,
          revision,
          artifact_path_rel,
          created_at_epoch_ms,
          updated_at_epoch_ms
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(project_name, plan_name, run_id, verification_name) DO UPDATE SET
          suite_run_id = excluded.suite_run_id,
          verification_order = excluded.verification_order,
          provider_type = excluded.provider_type,
          status = excluded.status,
          reason_code = excluded.reason_code,
          duration_ms = excluded.duration_ms,
          connection_ref = excluded.connection_ref,
          request_summary_json = excluded.request_summary_json,
          response_summary_json = excluded.response_summary_json,
          assertion_pass_count = excluded.assertion_pass_count,
          assertion_fail_count = excluded.assertion_fail_count,
          assertion_blocked_count = excluded.assertion_blocked_count,
          revision = excluded.revision,
          artifact_path_rel = excluded.artifact_path_rel,
          updated_at_epoch_ms = excluded.updated_at_epoch_ms`,
      ).run(
        plan.plan_run_pk,
        args.projectName,
        planName,
        runId,
        p.suiteRunId ?? null,
        verificationName,
        p.verificationOrder,
        p.providerType,
        p.status,
        p.reasonCode ?? null,
        p.durationMs ?? null,
        p.connectionRef ?? null,
        requestSummaryJson,
        responseSummaryJson,
        assertions.filter((assertion) => assertion.status === "pass").length,
        assertions.filter((assertion) => assertion.status === "fail").length,
        assertions.filter((assertion) => assertion.status === "blocked").length,
        nextRevision,
        p.artifactPathRel ?? null,
        createdAt,
        p.updatedAtEpochMs,
      );

      const row = db
        .prepare(
          "SELECT external_verification_pk FROM external_verifications WHERE project_name = ? AND plan_name = ? AND run_id = ? AND verification_name = ?",
        )
        .get(args.projectName, planName, runId, verificationName);
      if (typeof row?.external_verification_pk !== "number")
        throw new Error("external_verification_missing_after_upsert");
      for (const assertion of assertions) {
        db.prepare(
          `INSERT INTO external_verification_assertions (
            external_verification_pk,
            assertion_id,
            actual_path,
            operator,
            status,
            expected_summary_text,
            actual_summary_text,
            reason_code
          )
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(external_verification_pk, assertion_id) DO UPDATE SET
            actual_path = excluded.actual_path,
            operator = excluded.operator,
            status = excluded.status,
            expected_summary_text = excluded.expected_summary_text,
            actual_summary_text = excluded.actual_summary_text,
            reason_code = excluded.reason_code`,
        ).run(
          row.external_verification_pk,
          assertion.id,
          assertion.actualPath,
          assertion.operator,
          assertion.status,
          assertion.expectedSummaryText,
          assertion.actualSummaryText,
          assertion.reasonCode,
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
    return failure(
      "external_verification_state_persist_failed",
      "External-verification state could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}
