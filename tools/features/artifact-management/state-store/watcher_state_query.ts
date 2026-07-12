import { promises as fs } from "node:fs";
import path from "node:path";
import { openReadOnlyRunStateStore } from "./run_state_query";
import { sanitizePersistedWatcherJson } from "./watcher_state_store";
import type { RunStateDatabase } from "./model/run_state_store.model";

const PROJECTION_VERSION = 1;
const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 200;
const MAX_ATTEMPT_LIMIT = 100;
const STATUSES = new Set([
  "in_progress",
  "pass",
  "fail_assertion",
  "blocked_dependency",
  "blocked_runtime",
]);
const OUTCOMES = new Set(["verified", "failed_expectation", "timed_out", "blocked"]);

type Window = { offset: number; limit: number };
type Filters = {
  planName?: string | undefined;
  runId?: string | undefined;
  suiteRunId?: string | undefined;
  watcherName?: string | undefined;
  providerType?: string | undefined;
  status?: string | string[] | undefined;
  outcome?: string | string[] | undefined;
  reasonCode?: string | undefined;
  startedFromEpochMs?: number | undefined;
  startedToEpochMs?: number | undefined;
  completedFromEpochMs?: number | undefined;
  completedToEpochMs?: number | undefined;
  deadlineFromEpochMs?: number | undefined;
  deadlineToEpochMs?: number | undefined;
};
type Detail = {
  continuation?: boolean | undefined;
  lastObservation?: boolean | undefined;
  lastAssertion?: boolean | undefined;
  ownerLease?: boolean | undefined;
  attempts?: Window | undefined;
};
type Input = {
  projectName: string;
  filters?: Filters | undefined;
  sort?: { field?: string | undefined; direction?: "asc" | "desc" | undefined } | undefined;
  page?: { pageSize?: number | undefined; cursor?: string | null | undefined } | undefined;
  detail?: Detail | undefined;
};
type SortTuple = { startedAtEpochMs: number; watcherRunPk: number };
type Cursor = {
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
  stateSurface: "watcher_state";
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

function canonicalQuery(input: Input): Record<string, unknown> {
  const filters = input.filters ?? {};
  const statuses =
    filters.status === undefined
      ? undefined
      : Array.isArray(filters.status)
        ? [...filters.status].sort()
        : [filters.status];
  const outcomes =
    filters.outcome === undefined
      ? undefined
      : Array.isArray(filters.outcome)
        ? [...filters.outcome].sort()
        : [filters.outcome];
  return {
    projectName: input.projectName,
    filters: {
      ...filters,
      ...(statuses ? { status: statuses } : {}),
      ...(outcomes ? { outcome: outcomes } : {}),
    },
    sort: { field: "startedAtEpochMs", direction: input.sort?.direction ?? "desc" },
    ...(input.detail ? { detail: input.detail } : {}),
  };
}

function fingerprint(input: Input): string {
  const crypto = require("node:crypto") as {
    createHash(name: string): { update(value: string): { digest(encoding: string): string } };
  };
  return crypto
    .createHash("sha256")
    .update(JSON.stringify(canonicalQuery(input)))
    .digest("hex");
}

function encodeCursor(cursor: Cursor): string {
  return Buffer.from(JSON.stringify(cursor), "utf8").toString("base64url");
}

function decodeCursor(
  value: string,
  expectedFingerprint: string,
  direction: "asc" | "desc",
): Cursor | Failure {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (typeof parsed !== "object" || parsed === null) throw new Error("invalid");
    const cursor = parsed as Partial<Cursor>;
    const last = cursor.last as Partial<SortTuple> | undefined;
    if (
      cursor.version !== 1 ||
      cursor.projectionVersion !== PROJECTION_VERSION ||
      cursor.sortField !== "startedAtEpochMs" ||
      (cursor.sortDirection !== "asc" && cursor.sortDirection !== "desc") ||
      typeof cursor.queryFingerprint !== "string" ||
      !last ||
      !Number.isInteger(last.startedAtEpochMs) ||
      !Number.isInteger(last.watcherRunPk) ||
      (last.watcherRunPk as number) < 1
    )
      throw new Error("invalid");
    if (cursor.queryFingerprint !== expectedFingerprint || cursor.sortDirection !== direction)
      throw new Error("mismatch");
    return {
      version: 1,
      projectionVersion: 1,
      queryFingerprint: expectedFingerprint,
      sortField: "startedAtEpochMs",
      sortDirection: direction,
      last: last as SortTuple,
    };
  } catch (error) {
    return failure(
      "watcher_state_cursor_invalid",
      error instanceof Error && error.message === "mismatch"
        ? "watcher_state cursor does not match the current query"
        : "watcher_state cursor is invalid",
      "restart_watcher_state_query",
    );
  }
}

function validateFilters(filters: Filters): Failure | undefined {
  const supported = new Set([
    "planName",
    "runId",
    "suiteRunId",
    "watcherName",
    "providerType",
    "status",
    "outcome",
    "reasonCode",
    "startedFromEpochMs",
    "startedToEpochMs",
    "completedFromEpochMs",
    "completedToEpochMs",
    "deadlineFromEpochMs",
    "deadlineToEpochMs",
  ]);
  if (Object.keys(filters).some((key) => !supported.has(key)))
    return failure(
      "watcher_state_query_invalid",
      "unsupported watcher_state filter",
      "correct_watcher_state_query",
    );
  const identifiers = [
    filters.planName,
    filters.runId,
    filters.suiteRunId,
    filters.watcherName,
    filters.providerType,
    filters.reasonCode,
  ];
  if (identifiers.some((value) => value !== undefined && value.trim().length === 0))
    return failure(
      "watcher_state_query_invalid",
      "watcher_state identifiers must not be empty",
      "correct_watcher_state_query",
    );
  for (const [name, value, allowed] of [
    ["status", filters.status, STATUSES],
    ["outcome", filters.outcome, OUTCOMES],
  ] as const) {
    if (value !== undefined) {
      const values = Array.isArray(value) ? value : [value];
      if (
        !values.length ||
        values.length > allowed.size ||
        new Set(values).size !== values.length ||
        values.some((entry) => !allowed.has(entry))
      )
        return failure(
          "watcher_state_query_invalid",
          `${name} contains an unknown value`,
          "correct_watcher_state_query",
        );
    }
  }
  const ranges: Array<[number | undefined, number | undefined, string]> = [
    [filters.startedFromEpochMs, filters.startedToEpochMs, "started"],
    [filters.completedFromEpochMs, filters.completedToEpochMs, "completed"],
    [filters.deadlineFromEpochMs, filters.deadlineToEpochMs, "deadline"],
  ];
  if (ranges.some(([from, to]) => from !== undefined && to !== undefined && from > to))
    return failure(
      "watcher_state_query_invalid",
      "watcher_state time range is contradictory",
      "correct_watcher_state_query",
    );
  return undefined;
}

function validateDetail(detail: Detail | undefined): Failure | undefined {
  if (!detail?.attempts) return undefined;
  if (
    !Number.isInteger(detail.attempts.offset) ||
    detail.attempts.offset < 0 ||
    !Number.isInteger(detail.attempts.limit) ||
    detail.attempts.limit < 1 ||
    detail.attempts.limit > MAX_ATTEMPT_LIMIT
  )
    return failure(
      "watcher_state_query_invalid",
      "attempt window limit must be between 1 and 100",
      "correct_watcher_state_query",
    );
  return undefined;
}

function cursorPredicate(direction: "asc" | "desc"): string {
  const operator = direction === "desc" ? "<" : ">";
  return `(wr.started_at_epoch_ms ${operator} ? OR (wr.started_at_epoch_ms = ? AND wr.watcher_run_pk ${operator} ?))`;
}

function parseJson(value: unknown): { ok: true; value: unknown } | { ok: false } {
  return typeof value === "string" ? sanitizePersistedWatcherJson(value) : { ok: false };
}

async function artifactLink(
  workspaceRootAbs: string,
  row: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const pathRel = typeof row.artifact_path_rel === "string" ? row.artifact_path_rel : undefined;
  if (!pathRel) return { artifactLinkStatus: "missing" };
  const root = path.resolve(workspaceRootAbs);
  const resolved = path.resolve(root, pathRel);
  if (
    path.isAbsolute(pathRel) ||
    path.win32.isAbsolute(pathRel) ||
    pathRel.split(/[\\/]/).includes("..") ||
    !resolved.startsWith(`${root}${path.sep}`)
  )
    return { artifactLinkStatus: "stale", artifactPathRel: pathRel };
  const exists = await fs
    .stat(resolved)
    .then(() => true)
    .catch(() => false);
  return { artifactLinkStatus: exists ? "linked" : "stale", artifactPathRel: pathRel };
}

function attemptsPage(
  database: RunStateDatabase,
  watcherRunPk: number,
  window: Window,
): Record<string, unknown> | Failure {
  const total = Number(
    database
      .prepare("SELECT count(*) AS total FROM watcher_attempts WHERE watcher_run_pk = ?")
      .get(watcherRunPk)?.total ?? 0,
  );
  const rows = database
    .prepare(
      "SELECT attempt_number AS attemptNumber, observed_at_epoch_ms AS observedAtEpochMs, status, reason_code AS reasonCode, duration_ms AS durationMs, observation_summary_json FROM watcher_attempts WHERE watcher_run_pk = ? ORDER BY attempt_number ASC LIMIT ? OFFSET ?",
    )
    .all(watcherRunPk, window.limit, window.offset);
  const items: Array<Record<string, unknown>> = [];
  for (const attempt of rows) {
    const observation = parseJson(attempt.observation_summary_json);
    if (attempt.observation_summary_json !== null && !observation.ok)
      return failure(
        "watcher_state_unavailable",
        "requested Watcher attempt detail is not reconstructible",
        "inspect_watcher_state_store",
      );
    items.push({
      attemptNumber: attempt.attemptNumber,
      observedAtEpochMs: attempt.observedAtEpochMs,
      status: attempt.status,
      ...(attempt.reasonCode ? { reasonCode: attempt.reasonCode } : {}),
      ...(attempt.durationMs !== null && attempt.durationMs !== undefined
        ? { durationMs: attempt.durationMs }
        : {}),
      ...(observation.ok ? { observationSummary: observation.value } : {}),
    });
  }
  return {
    offset: window.offset,
    limit: window.limit,
    returned: rows.length,
    total,
    hasMore: window.offset + rows.length < total,
    items,
  };
}

export async function queryWatcherState(args: {
  workspaceRootAbs: string;
  input: Input;
}): Promise<Success | Failure> {
  const input = args.input;
  const filterFailure = validateFilters(input.filters ?? {});
  if (filterFailure) return filterFailure;
  const detailFailure = validateDetail(input.detail);
  if (detailFailure) return detailFailure;
  if (input.sort?.field && input.sort.field !== "startedAtEpochMs")
    return failure(
      "watcher_state_query_invalid",
      "only startedAtEpochMs sorting is supported",
      "correct_watcher_state_query",
    );
  const direction = input.sort?.direction ?? "desc";
  const pageSize = input.page?.pageSize ?? DEFAULT_PAGE_SIZE;
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
    return failure(
      "watcher_state_query_invalid",
      "pageSize must be between 1 and 200",
      "correct_watcher_state_query",
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
        ? "watcher_state_store_not_ready"
        : store.reasonCode;
    return failure(
      reasonCode,
      "watcher_state SQLite query is unavailable",
      store.nextAction,
      store.reasonMeta,
    );
  }
  try {
    const filters = input.filters ?? {};
    const clauses = ["wr.project_name = ?"];
    const parameters: unknown[] = [input.projectName];
    const add = (sql: string, ...values: unknown[]) => {
      clauses.push(sql);
      parameters.push(...values);
    };
    if (filters.planName) add("wr.plan_name = ?", filters.planName);
    if (filters.runId) add("wr.run_id = ?", filters.runId);
    if (filters.suiteRunId) add("wr.suite_run_id = ?", filters.suiteRunId);
    if (filters.watcherName) add("wr.watcher_name = ?", filters.watcherName);
    if (filters.providerType) add("wr.provider_type = ?", filters.providerType);
    if (filters.reasonCode) add("wr.reason_code = ?", filters.reasonCode);
    if (filters.status) {
      const values = Array.isArray(filters.status) ? filters.status : [filters.status];
      add(`wr.status IN (${values.map(() => "?").join(",")})`, ...values);
    }
    if (filters.outcome) {
      const values = Array.isArray(filters.outcome) ? filters.outcome : [filters.outcome];
      add(`wr.outcome IN (${values.map(() => "?").join(",")})`, ...values);
    }
    if (filters.startedFromEpochMs !== undefined)
      add("wr.started_at_epoch_ms >= ?", filters.startedFromEpochMs);
    if (filters.startedToEpochMs !== undefined)
      add("wr.started_at_epoch_ms <= ?", filters.startedToEpochMs);
    if (filters.completedFromEpochMs !== undefined)
      add("wr.completed_at_epoch_ms >= ?", filters.completedFromEpochMs);
    if (filters.completedToEpochMs !== undefined)
      add("wr.completed_at_epoch_ms <= ?", filters.completedToEpochMs);
    if (filters.deadlineFromEpochMs !== undefined)
      add("wr.deadline_at_epoch_ms >= ?", filters.deadlineFromEpochMs);
    if (filters.deadlineToEpochMs !== undefined)
      add("wr.deadline_at_epoch_ms <= ?", filters.deadlineToEpochMs);
    if (cursorValue && !("reasonCode" in cursorValue)) {
      clauses.push(cursorPredicate(direction));
      parameters.push(
        cursorValue.last.startedAtEpochMs,
        cursorValue.last.startedAtEpochMs,
        cursorValue.last.watcherRunPk,
      );
    }
    const rows = store.database
      .prepare(
        `SELECT wr.* FROM watcher_runs wr WHERE ${clauses.join(" AND ")} ORDER BY wr.started_at_epoch_ms ${direction.toUpperCase()}, wr.watcher_run_pk ${direction.toUpperCase()} LIMIT ?`,
      )
      .all(...parameters, pageSize + 1);
    if (rows.length === 0)
      return failure(
        "watcher_state_unavailable",
        "no persisted Watcher state matches the requested query",
        "inspect_watcher_state_store",
      );
    const limited = rows.slice(0, pageSize);
    const items: Array<Record<string, unknown>> = [];
    for (const row of limited) {
      const item: Record<string, unknown> = {
        projectName: row.project_name,
        planName: row.plan_name,
        runId: row.run_id,
        ...(row.suite_run_id ? { suiteRunId: row.suite_run_id } : {}),
        watcherName: row.watcher_name,
        watcherIndex: Number(row.watcher_index),
        dependencyStepOrder: Number(row.dependency_step_order),
        providerType: row.provider_type,
        status: row.status,
        outcome: row.outcome,
        reasonCode: row.reason_code,
        active: row.status === "in_progress",
        startedAtEpochMs: Number(row.started_at_epoch_ms),
        deadlineAtEpochMs: Number(row.deadline_at_epoch_ms),
        ...(row.next_attempt_at_epoch_ms !== null && row.next_attempt_at_epoch_ms !== undefined
          ? { nextAttemptAtEpochMs: Number(row.next_attempt_at_epoch_ms) }
          : {}),
        ...(row.completed_at_epoch_ms !== null && row.completed_at_epoch_ms !== undefined
          ? { completedAtEpochMs: Number(row.completed_at_epoch_ms) }
          : {}),
        timeoutMs: Number(row.timeout_ms),
        pollIntervalMs: Number(row.poll_interval_ms),
        retryMax: Number(row.retry_max),
        attemptCount: Number(row.attempt_count),
        revision: Number(row.revision),
        ...(await artifactLink(args.workspaceRootAbs, row)),
      };
      const detail = input.detail;
      if (detail?.continuation) {
        const continuation = parseJson(row.continuation_json);
        if (!continuation.ok)
          return failure(
            "watcher_state_unavailable",
            "requested Watcher continuation is absent or not reconstructible",
            "inspect_watcher_state_store",
          );
        item.continuation = continuation.value;
      }
      if (detail?.lastObservation) {
        const observation = parseJson(row.last_observation_summary_json);
        if (!observation.ok)
          return failure(
            "watcher_state_unavailable",
            "requested Watcher observation is absent or not reconstructible",
            "inspect_watcher_state_store",
          );
        item.lastObservation = observation.value;
      }
      if (detail?.lastAssertion) {
        const assertion = parseJson(row.last_assertion_summary_json);
        if (!assertion.ok)
          return failure(
            "watcher_state_unavailable",
            "requested Watcher assertion is absent or not reconstructible",
            "inspect_watcher_state_store",
          );
        item.lastAssertion = assertion.value;
      }
      if (detail?.ownerLease) item.ownerLease = { status: "not_persisted" };
      if (detail?.attempts) {
        const attempts = attemptsPage(store.database, Number(row.watcher_run_pk), detail.attempts);
        if ("ok" in attempts && attempts.ok === false) return attempts as Failure;
        item.attempts = attempts as Record<string, unknown>;
      }
      items.push(item);
    }
    const result: Success = {
      ok: true,
      stateSurface: "watcher_state",
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
          watcherRunPk: Number(last.watcher_run_pk),
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
      "watcher_state_unavailable",
      "watcher_state SQLite query is unavailable",
      "retry_state_store",
    );
  } finally {
    store.close();
  }
}

export type {
  Input as WatcherStateQueryInput,
  Success as WatcherStateQuerySuccess,
  Failure as WatcherStateQueryFailure,
};
