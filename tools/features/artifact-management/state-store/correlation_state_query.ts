import { createHash } from "node:crypto";
import { openReadOnlyRunStateStore } from "./run_state_query";
import type { RunStateDatabase } from "./model/run_state_store.model";

const PROJECTION_VERSION = 1;
const DEFAULT_PAGE_SIZE = 25;
const MAX_PAGE_SIZE = 100;
const DETAIL_MAX = 50;
const STATUSES = new Set(["collecting", "correlated", "fail_closed"]);
const KEY_TYPES = new Set(["traceId", "requestId", "messageId"]);
const DETAIL_SECTIONS = new Set(["keys", "lineExpectations", "probeObservations"]);

type Window = { offset: number; limit: number };
type CorrelationFilters = {
  planName?: string | undefined;
  runId?: string | undefined;
  suiteRunId?: string | undefined;
  correlationSessionId?: string | undefined;
  status?: string | string[] | undefined;
  reasonCode?: string | undefined;
  keyType?: string | undefined;
  keyValueExact?: string | undefined;
  keyValueSha256?: string | undefined;
  strictLineKey?: string | undefined;
  probeId?: string | undefined;
  logicalServiceId?: string | undefined;
  runtimeInstanceId?: string | undefined;
  startedFromEpochMs?: number | undefined;
  startedToEpochMs?: number | undefined;
  correlatedFromEpochMs?: number | undefined;
  correlatedToEpochMs?: number | undefined;
};
type CorrelationDetail = {
  select?: string[] | undefined;
  keys?: Window | undefined;
  lineExpectations?: Window | undefined;
  probeObservations?: Window | undefined;
};
type CorrelationQueryInput = {
  projectName: string;
  filters?: CorrelationFilters;
  sort?: { field?: string; direction?: "asc" | "desc" };
  page?: { pageSize?: number | undefined; cursor?: string | null | undefined };
  detail?: CorrelationDetail;
};
type SortTuple = { startedAtEpochMs: number; correlationRunPk: number };
type CursorPayload = {
  version: 1;
  projectionVersion: 1;
  queryFingerprint: string;
  sortField: "startedAtEpochMs";
  sortDirection: "asc" | "desc";
  last: SortTuple;
};
type Failure = {
  ok: false;
  reasonCode: string;
  reason: string;
  nextAction: string;
  reasonMeta?: Record<string, unknown>;
};
type Success = {
  ok: true;
  stateSurface: "correlation_state";
  projectName: string;
  projectionVersion: 1;
  pageSize: number;
  sort: { field: "startedAtEpochMs"; direction: "asc" | "desc" };
  items: Array<Record<string, unknown>>;
  nextCursor?: string;
};

function failure(
  reasonCode: string,
  reason: string,
  nextAction: string,
  reasonMeta?: Record<string, unknown>,
): Failure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function sha256Exact(value: string): string {
  return createHash("sha256").update(Buffer.from(value, "utf8")).digest("hex");
}

function canonicalQuery(input: CorrelationQueryInput): Record<string, unknown> {
  const filters = input.filters ?? {};
  let statuses: string[] | undefined;
  if (filters.status !== undefined)
    statuses = Array.isArray(filters.status) ? [...filters.status].sort() : [filters.status];
  const detail = input.detail
    ? {
        select: [...(input.detail.select ?? [])].sort(),
        ...(input.detail.keys ? { keys: input.detail.keys } : {}),
        ...(input.detail.lineExpectations
          ? { lineExpectations: input.detail.lineExpectations }
          : {}),
        ...(input.detail.probeObservations
          ? { probeObservations: input.detail.probeObservations }
          : {}),
      }
    : undefined;
  return {
    projectName: input.projectName,
    filters: {
      ...filters,
      ...(statuses ? { status: statuses } : {}),
      ...(filters.keyValueExact !== undefined ? { keyValueExact: filters.keyValueExact } : {}),
    },
    sort: { field: "startedAtEpochMs", direction: input.sort?.direction ?? "desc" },
    ...(detail ? { detail } : {}),
  };
}

function fingerprint(input: CorrelationQueryInput): string {
  return createHash("sha256")
    .update(JSON.stringify(canonicalQuery(input)))
    .digest("hex");
}

function encodeCursor(payload: CursorPayload): string {
  return Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
}

function decodeCursor(
  value: string,
  expectedFingerprint: string,
  direction: "asc" | "desc",
): CursorPayload | Failure {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (typeof parsed !== "object" || parsed === null) throw new Error("invalid");
    const cursor = parsed as Partial<CursorPayload>;
    const last = cursor.last as Partial<SortTuple> | undefined;
    if (
      cursor.version !== 1 ||
      cursor.projectionVersion !== PROJECTION_VERSION ||
      cursor.sortField !== "startedAtEpochMs" ||
      !last ||
      !Number.isInteger(last.startedAtEpochMs) ||
      !Number.isInteger(last.correlationRunPk) ||
      (last.correlationRunPk as number) < 1 ||
      typeof cursor.queryFingerprint !== "string" ||
      (cursor.sortDirection !== "asc" && cursor.sortDirection !== "desc")
    )
      throw new Error("invalid");
    if (cursor.queryFingerprint !== expectedFingerprint || cursor.sortDirection !== direction)
      return failure(
        "correlation_state_cursor_query_mismatch",
        "correlation_state cursor does not match the current query shape",
        "restart_correlation_state_query",
      );
    const validLast = last as SortTuple;
    return {
      version: 1,
      projectionVersion: 1,
      queryFingerprint: expectedFingerprint,
      sortField: "startedAtEpochMs",
      sortDirection: direction,
      last: validLast,
    };
  } catch {
    return failure(
      "correlation_state_cursor_invalid",
      "correlation_state cursor is invalid",
      "restart_correlation_state_query",
    );
  }
}

function validateDetail(detail: CorrelationDetail | undefined): Failure | undefined {
  if (!detail) return undefined;
  if (!detail.select)
    return failure(
      "correlation_state_query_invalid",
      "correlation_state detail.select is required",
      "correct_correlation_state_query",
    );
  if (
    detail.select.length < 1 ||
    detail.select.length > 3 ||
    detail.select.some((section) => !DETAIL_SECTIONS.has(section)) ||
    new Set(detail.select).size !== detail.select.length
  )
    return failure(
      "correlation_state_query_invalid",
      "detail contains an unknown or duplicate section",
      "correct_correlation_state_query",
    );
  for (const section of detail.select) {
    const window = detail[section as keyof CorrelationDetail] as Window | undefined;
    if (!window)
      return failure(
        "correlation_state_detail_window_required",
        `detail.${section} window is required`,
        "provide_correlation_state_detail_window",
      );
    if (
      !Number.isInteger(window.offset) ||
      window.offset < 0 ||
      !Number.isInteger(window.limit) ||
      window.limit < 1 ||
      window.limit > DETAIL_MAX
    )
      return failure(
        "correlation_state_query_invalid",
        `detail.${section} window must have limit between 1 and 50`,
        "correct_correlation_state_query",
      );
  }
  return undefined;
}

function validateFilters(filters: CorrelationFilters): Failure | undefined {
  const supported = new Set([
    "planName",
    "runId",
    "suiteRunId",
    "correlationSessionId",
    "status",
    "reasonCode",
    "keyType",
    "keyValueExact",
    "keyValueSha256",
    "strictLineKey",
    "probeId",
    "logicalServiceId",
    "runtimeInstanceId",
    "startedFromEpochMs",
    "startedToEpochMs",
    "correlatedFromEpochMs",
    "correlatedToEpochMs",
  ]);
  if (Object.keys(filters).some((key) => !supported.has(key)))
    return failure(
      "correlation_state_query_invalid",
      "unsupported correlation_state filter",
      "correct_correlation_state_query",
    );
  const identifiers = [
    filters.planName,
    filters.runId,
    filters.suiteRunId,
    filters.correlationSessionId,
    filters.reasonCode,
    filters.keyType,
    filters.strictLineKey,
    filters.probeId,
    filters.logicalServiceId,
    filters.runtimeInstanceId,
  ];
  if (identifiers.some((value) => value !== undefined && value.trim().length === 0))
    return failure(
      "correlation_state_query_invalid",
      "correlation_state identifiers must not be empty",
      "correct_correlation_state_query",
    );
  if (filters.status !== undefined) {
    const statuses = Array.isArray(filters.status) ? filters.status : [filters.status];
    if (
      statuses.length === 0 ||
      statuses.length > 3 ||
      statuses.some((status) => !STATUSES.has(status))
    )
      return failure(
        "correlation_state_query_invalid",
        "status contains an unknown value",
        "correct_correlation_state_query",
      );
  }
  if (filters.keyType && !KEY_TYPES.has(filters.keyType))
    return failure(
      "correlation_state_query_invalid",
      "keyType is unsupported",
      "correct_correlation_state_query",
    );
  if (
    (filters.keyValueExact !== undefined || filters.keyValueSha256 !== undefined) &&
    !filters.keyType
  )
    return failure(
      "correlation_state_key_filter_invalid",
      "keyType is required for key lookup",
      "correct_correlation_state_query",
    );
  if (filters.keyValueExact !== undefined && filters.keyValueSha256 !== undefined)
    return failure(
      "correlation_state_key_filter_invalid",
      "keyValueExact and keyValueSha256 are mutually exclusive",
      "correct_correlation_state_query",
    );
  if (
    filters.keyType &&
    filters.keyValueExact === undefined &&
    filters.keyValueSha256 === undefined
  )
    return failure(
      "correlation_state_key_filter_invalid",
      "keyType requires a key lookup",
      "correct_correlation_state_query",
    );
  if (filters.keyValueExact === "")
    return failure(
      "correlation_state_key_filter_invalid",
      "keyValueExact must not be empty",
      "correct_correlation_state_query",
    );
  if (filters.keyValueSha256 !== undefined && !/^[a-f0-9]{64}$/.test(filters.keyValueSha256))
    return failure(
      "correlation_state_key_filter_invalid",
      "keyValueSha256 must be lowercase hexadecimal SHA-256",
      "correct_correlation_state_query",
    );
  if (filters.keyValueExact !== undefined && Buffer.byteLength(filters.keyValueExact, "utf8") > 512)
    return failure(
      "correlation_state_key_filter_invalid",
      "keyValueExact exceeds the 512-byte bound",
      "correct_correlation_state_query",
    );
  if (
    filters.startedFromEpochMs !== undefined &&
    filters.startedToEpochMs !== undefined &&
    filters.startedFromEpochMs > filters.startedToEpochMs
  )
    return failure(
      "correlation_state_query_invalid",
      "started time range is contradictory",
      "correct_correlation_state_query",
    );
  if (
    filters.correlatedFromEpochMs !== undefined &&
    filters.correlatedToEpochMs !== undefined &&
    filters.correlatedFromEpochMs > filters.correlatedToEpochMs
  )
    return failure(
      "correlation_state_query_invalid",
      "correlated time range is contradictory",
      "correct_correlation_state_query",
    );
  return undefined;
}

function cursorPredicate(direction: "asc" | "desc"): string {
  const op = direction === "desc" ? "<" : ">";
  return `(cr.started_at_epoch_ms ${op} ? OR (cr.started_at_epoch_ms = ? AND cr.correlation_run_pk ${op} ?))`;
}

function cursorParameters(last: SortTuple): unknown[] {
  return [last.startedAtEpochMs, last.startedAtEpochMs, last.correlationRunPk];
}

function detailPage(
  database: RunStateDatabase,
  sql: string,
  parameters: unknown[],
  window: Window,
): Record<string, unknown> {
  const total = Number(
    database.prepare(`SELECT count(*) AS total FROM (${sql})`).get(...parameters)?.total ?? 0,
  );
  const items = database
    .prepare(`${sql} LIMIT ? OFFSET ?`)
    .all(...parameters, window.limit, window.offset);
  return {
    offset: window.offset,
    limit: window.limit,
    returned: items.length,
    total,
    hasMore: window.offset + items.length < total,
    items,
  };
}

function artifactReference(
  database: RunStateDatabase,
  row: Record<string, unknown>,
): Record<string, unknown> {
  const artifact = database
    .prepare(
      "SELECT path_rel, checksum FROM artifacts WHERE project_name = ? AND artifact_kind = 'correlation' AND plan_name = ? AND run_id = ? ORDER BY artifact_id ASC LIMIT 1",
    )
    .get(row.project_name, row.plan_name, row.run_id);
  if (artifact?.path_rel)
    return {
      status: "linked",
      pathRel: artifact.path_rel,
      ...(artifact.checksum ? { checksum: artifact.checksum } : {}),
    };
  if (row.correlation_path_rel) return { status: "linked", pathRel: row.correlation_path_rel };
  return {
    status: "missing",
    reasonCode: "correlation_artifact_missing",
    nextAction: "rebuild_state_store",
  };
}

function addDetails(
  database: RunStateDatabase,
  row: Record<string, unknown>,
  item: Record<string, unknown>,
  detail: CorrelationDetail,
): void {
  const pk = row.correlation_run_pk;
  const selected = new Set(detail.select ?? []);
  if (selected.has("keys")) {
    const keyPage = detailPage(
      database,
      "SELECT key_type, key_value_sanitized AS sanitizedValue FROM correlation_keys WHERE correlation_run_pk = ? ORDER BY correlation_key_pk ASC",
      [pk],
      detail.keys!,
    );
    keyPage.items = (keyPage.items as Array<Record<string, unknown>>).map((key) => ({
      keyType: key.key_type,
      ...(typeof key.sanitizedValue === "string" ? { sanitizedValue: key.sanitizedValue } : {}),
    }));
    item.keys = keyPage;
  }
  if (selected.has("lineExpectations"))
    item.lineExpectations = detailPage(
      database,
      "SELECT sequence_order AS sequenceOrder, label, strict_line_key AS strictLineKey, selector_policy AS selectorPolicy, operator, expected_hit_delta AS expectedHitDelta, expected_min_hit_delta AS expectedMinHitDelta, expected_max_hit_delta AS expectedMaxHitDelta, status, reason_code AS reasonCode, first_hit_epoch_ms AS firstHitEpochMs, last_hit_epoch_ms AS lastHitEpochMs FROM correlation_line_expectations WHERE correlation_run_pk = ? ORDER BY sequence_order ASC, line_expectation_pk ASC",
      [pk],
      detail.lineExpectations!,
    );
  if (selected.has("probeObservations"))
    item.probeObservations = detailPage(
      database,
      "SELECT po.probe_id AS probeId, po.logical_service_id AS logicalServiceId, po.service_instance_id AS serviceInstanceId, po.runtime_instance_id AS runtimeInstanceId, po.probe_address_observed AS probeAddressObserved, po.observed_scope_state AS observedScopeState, po.scope_state_observed_at_epoch_ms AS scopeStateObservedAtEpochMs, po.scope_state_expires_at_epoch_ms AS scopeStateExpiresAtEpochMs, 'not_evaluated' AS currentRuntimeTruth, po.baseline_hit_count AS baselineHitCount, po.current_hit_count AS currentHitCount, po.observed_hit_delta AS observedHitDelta, po.sample_count AS sampleCount, po.first_observed_at_epoch_ms AS firstObservedAtEpochMs, po.last_observed_at_epoch_ms AS lastObservedAtEpochMs, po.last_hit_epoch_ms AS lastHitEpochMs, po.revision AS revision FROM correlation_probe_observations po JOIN correlation_line_expectations le ON le.line_expectation_pk = po.line_expectation_pk WHERE le.correlation_run_pk = ? ORDER BY po.probe_observation_pk ASC",
      [pk],
      detail.probeObservations!,
    );
}

export async function queryCorrelationState(args: {
  workspaceRootAbs: string;
  input: CorrelationQueryInput;
}): Promise<Success | Failure> {
  const input = args.input;
  const filters = input.filters ?? {};
  const detailFailure = validateDetail(input.detail);
  if (detailFailure) return detailFailure;
  const filterFailure = validateFilters(filters);
  if (filterFailure) return filterFailure;
  const direction = input.sort?.direction ?? "desc";
  if (input.sort?.field && input.sort.field !== "startedAtEpochMs")
    return failure(
      "correlation_state_query_invalid",
      "only startedAtEpochMs sorting is supported",
      "correct_correlation_state_query",
    );
  const pageSize = input.page?.pageSize ?? DEFAULT_PAGE_SIZE;
  if (pageSize < 1 || pageSize > MAX_PAGE_SIZE)
    return failure(
      "correlation_state_query_invalid",
      "pageSize must be between 1 and 100",
      "correct_correlation_state_query",
    );
  const queryFingerprint = fingerprint(input);
  const cursorValue = input.page?.cursor
    ? decodeCursor(input.page.cursor, queryFingerprint, direction)
    : undefined;
  if (cursorValue && "reasonCode" in cursorValue) return cursorValue;
  const store = await openReadOnlyRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: input.projectName,
  });
  if (!store.ok) {
    const reasonCode =
      store.reasonCode === "run_state_store_not_ready"
        ? "correlation_state_not_available"
        : store.reasonCode;
    return failure(
      reasonCode,
      "correlation_state SQLite query is unavailable",
      store.nextAction,
      store.reasonMeta,
    );
  }
  try {
    const clauses = ["cr.project_name = ?"];
    const parameters: unknown[] = [input.projectName];
    const add = (sql: string, ...values: unknown[]) => {
      clauses.push(sql);
      parameters.push(...values);
    };
    if (filters.planName) add("cr.plan_name = ?", filters.planName);
    if (filters.runId) add("cr.run_id = ?", filters.runId);
    if (filters.suiteRunId) add("cr.suite_run_id = ?", filters.suiteRunId);
    if (filters.correlationSessionId)
      add("cr.correlation_session_id = ?", filters.correlationSessionId);
    if (filters.reasonCode) add("cr.reason_code = ?", filters.reasonCode);
    if (filters.status) {
      const statuses = Array.isArray(filters.status) ? filters.status : [filters.status];
      add(`cr.status IN (${statuses.map(() => "?").join(",")})`, ...statuses);
    }
    if (filters.startedFromEpochMs !== undefined)
      add("cr.started_at_epoch_ms >= ?", filters.startedFromEpochMs);
    if (filters.startedToEpochMs !== undefined)
      add("cr.started_at_epoch_ms <= ?", filters.startedToEpochMs);
    if (filters.correlatedFromEpochMs !== undefined)
      add("cr.correlated_at_epoch_ms >= ?", filters.correlatedFromEpochMs);
    if (filters.correlatedToEpochMs !== undefined)
      add("cr.correlated_at_epoch_ms <= ?", filters.correlatedToEpochMs);
    if (filters.keyValueExact !== undefined || filters.keyValueSha256 !== undefined)
      add(
        "EXISTS (SELECT 1 FROM correlation_keys ck WHERE ck.correlation_run_pk = cr.correlation_run_pk AND ck.key_type = ? AND ck.key_value_hash = ?)",
        filters.keyType,
        filters.keyValueSha256 ?? sha256Exact(filters.keyValueExact!),
      );
    if (
      filters.strictLineKey ||
      filters.probeId ||
      filters.logicalServiceId ||
      filters.runtimeInstanceId
    ) {
      const relation: string[] = ["le.correlation_run_pk = cr.correlation_run_pk"];
      const relationParameters: unknown[] = [];
      if (filters.strictLineKey) {
        relation.push("le.strict_line_key = ?");
        relationParameters.push(filters.strictLineKey);
      }
      if (filters.probeId) {
        relation.push("po.probe_id = ?");
        relationParameters.push(filters.probeId);
      }
      if (filters.logicalServiceId) {
        relation.push("po.logical_service_id = ?");
        relationParameters.push(filters.logicalServiceId);
      }
      if (filters.runtimeInstanceId) {
        relation.push("po.runtime_instance_id = ?");
        relationParameters.push(filters.runtimeInstanceId);
      }
      add(
        `EXISTS (SELECT 1 FROM correlation_line_expectations le LEFT JOIN correlation_probe_observations po ON po.line_expectation_pk = le.line_expectation_pk WHERE ${relation.join(" AND ")})`,
        ...relationParameters,
      );
    }
    if (cursorValue && !("reasonCode" in cursorValue)) {
      clauses.push(cursorPredicate(direction));
      parameters.push(...cursorParameters(cursorValue.last));
    }
    const rows = store.database
      .prepare(
        `SELECT cr.* FROM correlation_runs cr WHERE ${clauses.join(" AND ")} ORDER BY cr.started_at_epoch_ms ${direction.toUpperCase()}, cr.correlation_run_pk ${direction.toUpperCase()} LIMIT ?`,
      )
      .all(...parameters, pageSize + 1);
    const limited = rows.slice(0, pageSize);
    const items = limited.map((row) => {
      const item: Record<string, unknown> = {
        projectName: row.project_name,
        planName: row.plan_name,
        runId: row.run_id,
        ...(row.suite_run_id ? { suiteRunId: row.suite_run_id } : {}),
        correlationSessionId: row.correlation_session_id,
        correlationStatus: row.status,
        isCorrelated: row.status === "correlated",
        reasonCode: row.reason_code,
        expectedLineCount: Number(row.expected_line_count),
        matchedLineCount: Number(row.matched_line_count),
        window: {
          startEpochMs: row.window_start_epoch_ms,
          endEpochMs: row.window_end_epoch_ms,
          maxWindowMs: Number(row.max_window_ms),
        },
        startedAtEpochMs: Number(row.started_at_epoch_ms),
        correlatedAtEpochMs: row.correlated_at_epoch_ms,
        revision: Number(row.revision),
        correlationArtifact: artifactReference(store.database, row),
      };
      if (input.detail) addDetails(store.database, row, item, input.detail);
      return item;
    });
    const result: Success = {
      ok: true,
      stateSurface: "correlation_state",
      projectName: input.projectName,
      projectionVersion: PROJECTION_VERSION,
      pageSize,
      sort: { field: "startedAtEpochMs", direction },
      items,
    };
    if (rows.length > pageSize && limited.length) {
      const last = limited.at(-1)!;
      result.nextCursor = encodeCursor({
        version: 1,
        projectionVersion: 1,
        queryFingerprint,
        sortField: "startedAtEpochMs",
        sortDirection: direction,
        last: {
          startedAtEpochMs: Number(last.started_at_epoch_ms),
          correlationRunPk: Number(last.correlation_run_pk),
        },
      });
    }
    return result;
  } catch (error) {
    const message = (error instanceof Error ? error.message : String(error)).toLowerCase();
    if (message.includes("locked") || message.includes("busy"))
      return failure("state_store_locked", "run-state SQLite store is locked", "retry_state_store");
    if (
      message.includes("corrupt") ||
      message.includes("malformed") ||
      message.includes("not a database")
    )
      return failure(
        "state_store_corrupt",
        "run-state SQLite store is corrupt",
        "rebuild_state_store",
      );
    if (message.includes("no such table") || message.includes("schema"))
      return failure(
        "state_store_schema_unsupported",
        "run-state SQLite schema is unsupported",
        "rebuild_state_store",
      );
    return failure(
      "correlation_state_not_available",
      "correlation_state SQLite query is unavailable",
      "retry_state_store",
    );
  } finally {
    store.close();
  }
}

export type {
  CorrelationQueryInput,
  CorrelationFilters,
  CorrelationDetail,
  Success as CorrelationStateQuerySuccess,
  Failure as CorrelationStateQueryFailure,
};
