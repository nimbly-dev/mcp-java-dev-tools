import { createHash } from "node:crypto";
import path from "node:path";
import type {
  OpenRunStateStore,
  CorrelationObservation,
  CorrelationObservationResult,
  CorrelationPersistenceFailure,
  CorrelationSession,
  CorrelationSessionResult,
} from "./model/run_state_store.model";

function isSafeRelativePath(pathRel: string): boolean {
  return (
    pathRel.length > 0 &&
    !path.isAbsolute(pathRel) &&
    !path.win32.isAbsolute(pathRel) &&
    !pathRel.split(/[\\/]/).includes("..")
  );
}
function correlationFailure(
  reasonCode: CorrelationPersistenceFailure["reasonCode"],
  reason: string,
  nextAction: CorrelationPersistenceFailure["nextAction"],
  reasonMeta?: Record<string, unknown>,
): CorrelationPersistenceFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}
function evaluateCorrelationObservation(
  observation: CorrelationObservation,
  observedHitDelta: number,
): "collecting" | "matched" | "fail_closed" {
  if (observation.operator === "exact")
    return observedHitDelta === observation.expectedHitDelta ? "matched" : "collecting";
  if (observation.operator === "at_least")
    return observedHitDelta >= (observation.expectedHitDelta ?? 0) ? "matched" : "collecting";
  if (observation.operator === "at_most")
    return observedHitDelta <= (observation.expectedHitDelta ?? 0) ? "matched" : "collecting";
  return observedHitDelta >= (observation.expectedMinHitDelta ?? 0) &&
    observedHitDelta <= (observation.expectedMaxHitDelta ?? 0)
    ? "matched"
    : "collecting";
}
function isValidCorrelationExpectation(observation: CorrelationObservation): boolean {
  if (observation.operator === "range") {
    const min = observation.expectedMinHitDelta;
    const max = observation.expectedMaxHitDelta;
    return (
      Number.isInteger(min) &&
      Number.isInteger(max) &&
      min !== undefined &&
      max !== undefined &&
      min >= 0 &&
      max >= min
    );
  }
  const expected = observation.expectedHitDelta;
  return Number.isInteger(expected) && expected !== undefined && expected >= 0;
}

export function persistCorrelationSession(args: {
  store: OpenRunStateStore;
  projectName: string;
  session: CorrelationSession;
}): CorrelationSessionResult {
  const session = args.session;
  if (
    args.projectName !== args.store.projectName ||
    !session.planName.trim() ||
    !session.runId.trim() ||
    !session.correlationSessionId.trim() ||
    !Number.isInteger(session.maxWindowMs) ||
    session.maxWindowMs <= 0 ||
    !Number.isInteger(session.startedAtEpochMs) ||
    !session.reasonCode.trim() ||
    (session.correlationPathRel !== undefined && !isSafeRelativePath(session.correlationPathRel)) ||
    (session.keyValue !== undefined && session.keyValue.length > 512)
  )
    return correlationFailure(
      "correlation_identity_invalid",
      "correlation session identity, bounded window, and portable Artifact path are required",
      "correct_correlation_input",
    );
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existing = db
        .prepare(
          "SELECT correlation_run_pk FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?",
        )
        .get(args.projectName, session.runId, session.correlationSessionId);
      if (existing && typeof existing.correlation_run_pk === "number")
        db.prepare(
          "UPDATE correlation_runs SET status = ?, reason_code = ?, max_window_ms = ?, correlation_path_rel = ?, revision = revision + 1 WHERE correlation_run_pk = ?",
        ).run(
          session.status,
          session.reasonCode,
          session.maxWindowMs,
          session.correlationPathRel ?? null,
          existing.correlation_run_pk,
        );
      else
        db.prepare(
          "INSERT INTO correlation_runs (project_name, plan_name, run_id, correlation_session_id, status, reason_code, expected_line_count, matched_line_count, max_window_ms, started_at_epoch_ms, correlation_path_rel) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)",
        ).run(
          args.projectName,
          session.planName,
          session.runId,
          session.correlationSessionId,
          session.status,
          session.reasonCode,
          session.maxWindowMs,
          session.startedAtEpochMs,
          session.correlationPathRel ?? null,
        );
      const run = db
        .prepare(
          "SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?",
        )
        .get(args.projectName, session.runId, session.correlationSessionId);
      if (typeof run?.correlation_run_pk !== "number" || typeof run.revision !== "number")
        throw new Error("correlation_run_missing");
      for (const expectation of session.expectations ?? [])
        db.prepare(
          "INSERT INTO correlation_line_expectations (correlation_run_pk, sequence_order, label, strict_line_key, selector_policy, operator, expected_hit_delta, expected_min_hit_delta, expected_max_hit_delta, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'collecting') ON CONFLICT(correlation_run_pk, sequence_order, strict_line_key) DO NOTHING",
        ).run(
          run.correlation_run_pk,
          expectation.sequenceOrder,
          expectation.label ?? null,
          expectation.strictLineKey,
          expectation.selectorPolicy,
          expectation.operator,
          expectation.expectedHitDelta ?? null,
          expectation.expectedMinHitDelta ?? null,
          expectation.expectedMaxHitDelta ?? null,
        );
      if (session.keyValue)
        db.prepare(
          "INSERT INTO correlation_keys (correlation_run_pk, key_type, key_value_sanitized, key_value_hash) VALUES (?, ?, NULL, ?) ON CONFLICT(correlation_run_pk, key_type, key_value_hash) DO NOTHING",
        ).run(
          run.correlation_run_pk,
          session.keyType,
          createHash("sha256").update(session.keyValue).digest("hex"),
        );
      db.exec("COMMIT;");
      return { ok: true, revision: run.revision };
    } catch (error) {
      try {
        db.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      throw error;
    }
  } catch (error) {
    return correlationFailure(
      "correlation_persist_failed",
      "correlation session could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}

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
    args.projectName !== args.store.projectName ||
    !args.planName.trim() ||
    !args.runId.trim() ||
    !args.correlationSessionId.trim() ||
    !Number.isInteger(args.maxWindowMs) ||
    args.maxWindowMs <= 0 ||
    !/^[\w.$]+#[\w$<>]+:\d+$/.test(o.strictLineKey) ||
    !o.probeId.trim() ||
    !o.runtimeInstanceId.trim() ||
    !Number.isInteger(o.sequenceOrder) ||
    o.sequenceOrder < 1 ||
    !Number.isInteger(o.baselineHitCount) ||
    !Number.isInteger(o.currentHitCount) ||
    o.currentHitCount < o.baselineHitCount ||
    !Number.isInteger(o.observedAtEpochMs) ||
    !isValidCorrelationExpectation(o)
  )
    return correlationFailure(
      "correlation_identity_invalid",
      "correlation identity, Strict Line Key, and bounded expectation are required",
      "correct_correlation_input",
    );
  try {
    const db = args.store.database;
    db.exec("BEGIN IMMEDIATE;");
    try {
      const existingRun = db
        .prepare(
          "SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?",
        )
        .get(args.projectName, args.runId, args.correlationSessionId);
      if (
        typeof o.expectedRevision === "number" &&
        typeof existingRun?.revision === "number" &&
        o.expectedRevision !== existingRun.revision
      ) {
        db.exec("ROLLBACK;");
        return correlationFailure(
          "correlation_revision_conflict",
          "correlation revision is stale",
          "resume_same_suite",
          { expectedRevision: o.expectedRevision, currentRevision: existingRun.revision },
        );
      }
      if (existingRun && typeof existingRun.correlation_run_pk === "number")
        db.prepare(
          "UPDATE correlation_runs SET revision = revision + 1 WHERE correlation_run_pk = ?",
        ).run(existingRun.correlation_run_pk);
      else
        db.prepare(
          "INSERT INTO correlation_runs (project_name, plan_name, run_id, correlation_session_id, status, reason_code, expected_line_count, matched_line_count, max_window_ms, started_at_epoch_ms) VALUES (?, ?, ?, ?, 'collecting', 'collecting', 0, 0, ?, ?)",
        ).run(
          args.projectName,
          args.planName,
          args.runId,
          args.correlationSessionId,
          args.maxWindowMs,
          o.observedAtEpochMs,
        );
      const run = db
        .prepare(
          "SELECT correlation_run_pk, revision FROM correlation_runs WHERE project_name = ? AND run_id = ? AND correlation_session_id = ?",
        )
        .get(args.projectName, args.runId, args.correlationSessionId);
      if (typeof run?.correlation_run_pk !== "number" || typeof run.revision !== "number")
        throw new Error("correlation_run_missing");
      const runPk = run.correlation_run_pk;
      db.prepare(
        "INSERT INTO correlation_line_expectations (correlation_run_pk, sequence_order, strict_line_key, selector_policy, operator, expected_hit_delta, expected_min_hit_delta, expected_max_hit_delta, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'collecting') ON CONFLICT(correlation_run_pk, sequence_order, strict_line_key) DO NOTHING",
      ).run(
        runPk,
        o.sequenceOrder,
        o.strictLineKey,
        o.selectorPolicy,
        o.operator,
        o.expectedHitDelta ?? null,
        o.expectedMinHitDelta ?? null,
        o.expectedMaxHitDelta ?? null,
      );
      const line = db
        .prepare(
          "SELECT line_expectation_pk FROM correlation_line_expectations WHERE correlation_run_pk = ? AND sequence_order = ? AND strict_line_key = ?",
        )
        .get(runPk, o.sequenceOrder, o.strictLineKey);
      if (typeof line?.line_expectation_pk !== "number")
        throw new Error("correlation_line_missing");
      const runtime = db
        .prepare(
          "SELECT runtime_instance_id FROM correlation_probe_observations WHERE line_expectation_pk = ? AND probe_id = ? LIMIT 1",
        )
        .get(line.line_expectation_pk, o.probeId);
      if (
        typeof runtime?.runtime_instance_id === "string" &&
        runtime.runtime_instance_id !== o.runtimeInstanceId
      ) {
        db.exec("ROLLBACK;");
        return correlationFailure(
          "correlation_runtime_instance_changed",
          "Probe runtime instance changed during an active observation",
          "resume_same_suite",
          {
            probeId: o.probeId,
            priorRuntimeInstanceId: runtime.runtime_instance_id,
            runtimeInstanceId: o.runtimeInstanceId,
          },
        );
      }
      const prior = db
        .prepare(
          "SELECT baseline_hit_count, current_hit_count FROM correlation_probe_observations WHERE line_expectation_pk = ? AND probe_id = ? AND runtime_instance_id = ?",
        )
        .get(line.line_expectation_pk, o.probeId, o.runtimeInstanceId);
      if (
        typeof prior?.current_hit_count === "number" &&
        o.currentHitCount < prior.current_hit_count
      ) {
        db.exec("ROLLBACK;");
        return correlationFailure(
          "correlation_hit_count_non_monotonic",
          "correlation hit count decreased within one runtime instance",
          "correct_correlation_input",
        );
      }
      const baselineHitCount =
        typeof prior?.baseline_hit_count === "number"
          ? prior.baseline_hit_count
          : o.baselineHitCount;
      const observedHitDelta = o.currentHitCount - baselineHitCount;
      if (
        (o.operator === "exact" || o.operator === "at_most") &&
        observedHitDelta > (o.expectedHitDelta ?? 0)
      ) {
        db.exec("ROLLBACK;");
        return correlationFailure(
          "correlation_expectation_exceeded",
          "correlation hit delta exceeds its bounded expectation",
          "correct_correlation_input",
          { observedHitDelta },
        );
      }
      if (o.operator === "range" && observedHitDelta > (o.expectedMaxHitDelta ?? 0)) {
        db.exec("ROLLBACK;");
        return correlationFailure(
          "correlation_expectation_exceeded",
          "correlation hit delta exceeds its bounded range",
          "correct_correlation_input",
          { observedHitDelta },
        );
      }
      const status = evaluateCorrelationObservation(o, observedHitDelta);
      db.prepare(
        "INSERT INTO correlation_probe_observations (line_expectation_pk, probe_id, runtime_instance_id, baseline_hit_count, current_hit_count, observed_hit_delta, first_observed_at_epoch_ms, last_observed_at_epoch_ms, sample_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1) ON CONFLICT(line_expectation_pk, probe_id, runtime_instance_id) DO UPDATE SET current_hit_count = excluded.current_hit_count, observed_hit_delta = excluded.observed_hit_delta, last_observed_at_epoch_ms = excluded.last_observed_at_epoch_ms, sample_count = correlation_probe_observations.sample_count + 1, revision = correlation_probe_observations.revision + 1",
      ).run(
        line.line_expectation_pk,
        o.probeId,
        o.runtimeInstanceId,
        baselineHitCount,
        o.currentHitCount,
        observedHitDelta,
        o.observedAtEpochMs,
        o.observedAtEpochMs,
      );
      db.prepare(
        "UPDATE correlation_line_expectations SET status = ?, reason_code = ?, last_hit_epoch_ms = ? WHERE line_expectation_pk = ?",
      ).run(
        status,
        status === "matched" ? "ok" : "collecting",
        o.observedAtEpochMs,
        line.line_expectation_pk,
      );
      const summary = db
        .prepare(
          "SELECT count(*) AS expected_line_count, sum(CASE WHEN status = 'matched' THEN 1 ELSE 0 END) AS matched_line_count FROM correlation_line_expectations WHERE correlation_run_pk = ?",
        )
        .get(runPk);
      const expectedLineCount =
        typeof summary?.expected_line_count === "number" ? summary.expected_line_count : 0;
      const matchedLineCount =
        typeof summary?.matched_line_count === "number" ? summary.matched_line_count : 0;
      db.prepare(
        "UPDATE correlation_runs SET expected_line_count = ?, matched_line_count = ?, status = CASE WHEN status = 'fail_closed' THEN status ELSE ? END, reason_code = CASE WHEN status = 'fail_closed' THEN reason_code ELSE ? END, correlated_at_epoch_ms = CASE WHEN status <> 'fail_closed' AND ? = ? AND ? > 0 THEN ? ELSE correlated_at_epoch_ms END WHERE correlation_run_pk = ?",
      ).run(
        expectedLineCount,
        matchedLineCount,
        matchedLineCount === expectedLineCount ? "correlated" : "collecting",
        matchedLineCount === expectedLineCount ? "ok" : "collecting",
        matchedLineCount,
        expectedLineCount,
        expectedLineCount,
        o.observedAtEpochMs,
        runPk,
      );
      db.exec("COMMIT;");
      return { ok: true, revision: run.revision, observedHitDelta, status };
    } catch (error) {
      try {
        db.exec("ROLLBACK;");
      } catch {
        /* transaction already closed */
      }
      throw error;
    }
  } catch (error) {
    return correlationFailure(
      "correlation_persist_failed",
      "correlation observation could not be persisted",
      "retry_state_store",
      { error: error instanceof Error ? error.message : String(error) },
    );
  }
}
