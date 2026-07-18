import type {
  OpenRunStateStore,
  RuntimeEvidenceCursor,
  RuntimeEvidenceCursorResult,
  CorrelationPersistenceFailure,
} from "./model/run_state_store.model";

function failure(
  reasonCode: CorrelationPersistenceFailure["reasonCode"],
  reason: string,
  reasonMeta?: Record<string, unknown>,
): RuntimeEvidenceCursorResult {
  return {
    ok: false,
    reasonCode,
    reason,
    nextAction: "retry_state_store",
    ...(reasonMeta ? { reasonMeta } : {}),
  };
}

function valid(c: RuntimeEvidenceCursor, projectName: string, store: OpenRunStateStore): boolean {
  return (
    projectName === store.projectName &&
    !!c.runId.trim() &&
    !!c.correlationSessionId.trim() &&
    !!c.probeId.trim() &&
    !!c.runtimeInstanceId.trim() &&
    !!c.streamRuntimeInstanceId.trim() &&
    Number.isInteger(c.lastSequence) &&
    c.lastSequence >= 0 &&
    Number.isInteger(c.streamResetEpoch) &&
    c.streamResetEpoch >= 0 &&
    Number.isInteger(c.latestObservationAtEpochMs) &&
    c.latestObservationAtEpochMs >= 0 &&
    !!c.reasonCode.trim() &&
    !!c.dedupeIdentity.trim() &&
    c.dedupeIdentity.length <= 512
  );
}

export function readRuntimeEvidenceCursor(args: {
  store: OpenRunStateStore;
  projectName: string;
  runId: string;
  correlationSessionId: string;
  probeId: string;
  runtimeInstanceId?: string;
}): RuntimeEvidenceCursor | undefined {
  const statement = args.store.database.prepare(
    `SELECT suite_run_id, run_id, correlation_session_id, probe_id, runtime_instance_id,
      last_sequence, stream_runtime_instance_id, stream_reset_epoch,
      latest_observation_at_epoch_ms, status, reason_code, dedupe_identity, revision
      FROM runtime_evidence_cursors
      WHERE project_name = ? AND run_id = ? AND correlation_session_id = ? AND probe_id = ?
      AND (? IS NULL OR runtime_instance_id = ?)
       ORDER BY last_sequence DESC`,
  );
  const rows = statement.all(
    args.projectName,
    args.runId,
    args.correlationSessionId,
    args.probeId,
    args.runtimeInstanceId ?? null,
    args.runtimeInstanceId ?? null,
  );
  if (!args.runtimeInstanceId && rows.length > 1)
    throw new Error("correlation_runtime_instance_ambiguous");
  const row = rows[0];
  if (!row) return undefined;
  return {
    ...(typeof row.suite_run_id === "string" ? { suiteRunId: row.suite_run_id } : {}),
    runId: String(row.run_id),
    correlationSessionId: String(row.correlation_session_id),
    probeId: String(row.probe_id),
    runtimeInstanceId: String(row.runtime_instance_id),
    lastSequence: Number(row.last_sequence),
    streamRuntimeInstanceId: String(row.stream_runtime_instance_id),
    streamResetEpoch: Number(row.stream_reset_epoch),
    latestObservationAtEpochMs: Number(row.latest_observation_at_epoch_ms),
    status:
      row.status === "matched" || row.status === "fail_closed" || row.status === "pending_artifact"
        ? row.status
        : "collecting",
    reasonCode: String(row.reason_code),
    dedupeIdentity: String(row.dedupe_identity),
    expectedRevision: Number(row.revision),
  };
}

export function upsertRuntimeEvidenceCursor(args: {
  store: OpenRunStateStore;
  projectName: string;
  cursor: RuntimeEvidenceCursor;
}): RuntimeEvidenceCursorResult {
  const c = args.cursor;
  if (!valid(c, args.projectName, args.store))
    return failure("correlation_identity_invalid", "runtime evidence cursor is invalid");
  const db = args.store.database;
  try {
    db.exec("BEGIN IMMEDIATE;");
    const existing = db
      .prepare(
        `SELECT runtime_evidence_cursor_pk, revision, last_sequence, stream_runtime_instance_id
       FROM runtime_evidence_cursors WHERE project_name = ? AND run_id = ?
       AND correlation_session_id = ? AND probe_id = ? AND runtime_instance_id = ?`,
      )
      .get(args.projectName, c.runId, c.correlationSessionId, c.probeId, c.runtimeInstanceId);
    if (
      existing &&
      typeof c.expectedRevision === "number" &&
      existing.revision !== c.expectedRevision
    ) {
      db.exec("ROLLBACK;");
      return failure("correlation_revision_conflict", "runtime evidence cursor revision is stale", {
        expectedRevision: c.expectedRevision,
        currentRevision: existing.revision,
      });
    }
    if (existing && existing.stream_runtime_instance_id !== c.streamRuntimeInstanceId) {
      db.exec("ROLLBACK;");
      return failure(
        "correlation_runtime_instance_changed",
        "runtime evidence stream identity changed",
        { prior: existing.stream_runtime_instance_id, current: c.streamRuntimeInstanceId },
      );
    }
    if (existing && c.lastSequence < Number(existing.last_sequence)) {
      db.exec("ROLLBACK;");
      return failure(
        "correlation_hit_count_non_monotonic",
        "runtime evidence cursor moved backwards",
      );
    }
    db.prepare(
      `INSERT INTO runtime_evidence_cursors
       (project_name, suite_run_id, run_id, correlation_session_id, probe_id, runtime_instance_id,
        last_sequence, stream_runtime_instance_id, stream_reset_epoch, latest_observation_at_epoch_ms,
        status, reason_code, dedupe_identity)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(project_name, run_id, correlation_session_id, probe_id, runtime_instance_id)
       DO UPDATE SET suite_run_id=excluded.suite_run_id, last_sequence=excluded.last_sequence,
        stream_reset_epoch=excluded.stream_reset_epoch, latest_observation_at_epoch_ms=excluded.latest_observation_at_epoch_ms,
        status=excluded.status, reason_code=excluded.reason_code, dedupe_identity=excluded.dedupe_identity,
        revision=runtime_evidence_cursors.revision + 1`,
    ).run(
      args.projectName,
      c.suiteRunId ?? null,
      c.runId,
      c.correlationSessionId,
      c.probeId,
      c.runtimeInstanceId,
      c.lastSequence,
      c.streamRuntimeInstanceId,
      c.streamResetEpoch,
      c.latestObservationAtEpochMs,
      c.status,
      c.reasonCode,
      c.dedupeIdentity,
    );
    const row = db
      .prepare(
        `SELECT revision FROM runtime_evidence_cursors WHERE project_name = ? AND run_id = ?
       AND correlation_session_id = ? AND probe_id = ? AND runtime_instance_id = ?`,
      )
      .get(args.projectName, c.runId, c.correlationSessionId, c.probeId, c.runtimeInstanceId);
    db.exec("COMMIT;");
    return { ok: true, revision: Number(row?.revision ?? 0), cursor: c };
  } catch (error) {
    try {
      db.exec("ROLLBACK;");
    } catch {
      /* transaction already closed */
    }
    return failure("correlation_persist_failed", "runtime evidence cursor could not be persisted", {
      error: error instanceof Error ? error.message : String(error),
    });
  }
}
