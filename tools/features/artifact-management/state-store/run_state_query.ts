import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { cutoverMarkerPath, cutoverSentinelPath } from "./state_store_cutover_marker";
import type { RunStateDatabase } from "./model/run_state_store.model";

const { DatabaseSync } = require("node:sqlite") as {
  DatabaseSync: new (location: string, options?: { readOnly?: boolean }) => RunStateDatabase;
};

const PROJECTION_VERSION = 1;
const MAX_PAGE_SIZE = 100;
const DEFAULT_PAGE_SIZE = 25;
const STATUS_VALUES = new Set([
  "pass",
  "fail",
  "blocked",
  "partial_fail",
  "in_progress",
  "executed",
  "skipped",
]);

type QueryInput = {
  projectName: string;
  planName?: string;
  runId?: string;
  suiteRunId?: string;
  executionProfile?: string;
  status?: string | string[];
  activePhase?: "trigger" | "watchers" | "external_verification";
  startedFromEpochMs?: number;
  startedToEpochMs?: number;
  completedFromEpochMs?: number;
  completedToEpochMs?: number;
  sortDirection?: "asc" | "desc";
  pageSize?: number;
  cursor?: string;
};

type SortTuple = {
  updatedAtEpochMs: number;
  stateKind: "suite" | "plan";
  projectName: string;
  planName: string;
  runId: string;
  suiteRunId: string;
};

type CursorPayload = {
  version: 1;
  projectionVersion: 1;
  queryFingerprint: string;
  sortDirection: "asc" | "desc";
  last: SortTuple;
};

type QueryFailure = {
  ok: false;
  reasonCode: string;
  reason: string;
  nextAction: string;
  reasonMeta?: Record<string, unknown>;
};

type QuerySuccess = {
  ok: true;
  stateSurface: "run_state";
  projectName: string;
  projectionVersion: 1;
  pageSize: number;
  sort: { field: "updatedAtEpochMs"; direction: "asc" | "desc" };
  items: Array<Record<string, unknown>>;
  nextCursor?: string;
};

function failure(
  reasonCode: string,
  reason: string,
  nextAction: string,
  reasonMeta?: Record<string, unknown>,
): QueryFailure {
  return { ok: false, reasonCode, reason, nextAction, ...(reasonMeta ? { reasonMeta } : {}) };
}

function canonicalQuery(input: QueryInput): Record<string, unknown> {
  const statuses =
    input.status === undefined
      ? undefined
      : Array.isArray(input.status)
        ? [...input.status].sort()
        : [input.status];
  return {
    projectName: input.projectName.trim(),
    ...(input.planName ? { planName: input.planName.trim() } : {}),
    ...(input.runId ? { runId: input.runId.trim() } : {}),
    ...(input.suiteRunId ? { suiteRunId: input.suiteRunId.trim() } : {}),
    ...(input.executionProfile ? { executionProfile: input.executionProfile.trim() } : {}),
    ...(statuses ? { status: statuses } : {}),
    ...(input.activePhase ? { activePhase: input.activePhase } : {}),
    ...(input.startedFromEpochMs !== undefined
      ? { startedFromEpochMs: input.startedFromEpochMs }
      : {}),
    ...(input.startedToEpochMs !== undefined ? { startedToEpochMs: input.startedToEpochMs } : {}),
    ...(input.completedFromEpochMs !== undefined
      ? { completedFromEpochMs: input.completedFromEpochMs }
      : {}),
    ...(input.completedToEpochMs !== undefined
      ? { completedToEpochMs: input.completedToEpochMs }
      : {}),
    sortDirection: input.sortDirection ?? "desc",
  };
}

function fingerprint(input: QueryInput): string {
  return createHash("sha256")
    .update(JSON.stringify(canonicalQuery(input)))
    .digest("hex");
}

function encodeCursor(payload: CursorPayload): string {
  return Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
}

function cursorPredicate(direction: "asc" | "desc"): string {
  const operator = direction === "desc" ? "<" : ">";
  return `(
    updated_at_epoch_ms ${operator} ? OR
    (updated_at_epoch_ms = ? AND state_kind ${operator} ?) OR
    (updated_at_epoch_ms = ? AND state_kind = ? AND project_name ${operator} ?) OR
    (updated_at_epoch_ms = ? AND state_kind = ? AND project_name = ? AND plan_name ${operator} ?) OR
    (updated_at_epoch_ms = ? AND state_kind = ? AND project_name = ? AND plan_name = ? AND run_id ${operator} ?) OR
    (updated_at_epoch_ms = ? AND state_kind = ? AND project_name = ? AND plan_name = ? AND run_id = ? AND suite_run_id ${operator} ?)
  )`;
}

function cursorParameters(last: SortTuple): unknown[] {
  return [
    last.updatedAtEpochMs,
    last.updatedAtEpochMs,
    last.stateKind,
    last.updatedAtEpochMs,
    last.stateKind,
    last.projectName,
    last.updatedAtEpochMs,
    last.stateKind,
    last.projectName,
    last.planName,
    last.updatedAtEpochMs,
    last.stateKind,
    last.projectName,
    last.planName,
    last.runId,
    last.updatedAtEpochMs,
    last.stateKind,
    last.projectName,
    last.planName,
    last.runId,
    last.suiteRunId,
  ];
}

function decodeCursor(
  value: string,
  expectedFingerprint: string,
  direction: "asc" | "desc",
): CursorPayload | QueryFailure {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
    if (typeof parsed !== "object" || parsed === null) throw new Error("cursor_not_object");
    const cursor = parsed as Partial<CursorPayload>;
    const last = cursor.last as Partial<SortTuple> | undefined;
    if (
      cursor.version !== 1 ||
      cursor.projectionVersion !== PROJECTION_VERSION ||
      !last ||
      typeof last.updatedAtEpochMs !== "number" ||
      (last.stateKind !== "suite" && last.stateKind !== "plan") ||
      typeof last.projectName !== "string" ||
      typeof last.planName !== "string" ||
      typeof last.runId !== "string" ||
      typeof last.suiteRunId !== "string"
    )
      throw new Error("cursor_invalid");
    if (cursor.queryFingerprint !== expectedFingerprint || cursor.sortDirection !== direction)
      return failure(
        "run_state_cursor_query_mismatch",
        "run_state cursor does not match the current query shape",
        "restart_run_state_query",
      );
    return {
      version: 1,
      projectionVersion: 1,
      queryFingerprint: expectedFingerprint,
      sortDirection: direction,
      last: last as SortTuple,
    };
  } catch {
    return failure("run_state_cursor_invalid", "run_state cursor is invalid", "retry_state_store");
  }
}

function classify(error: unknown): QueryFailure {
  const message = (error instanceof Error ? error.message : String(error)).toLowerCase();
  if (message.includes("locked") || message.includes("busy"))
    return failure("state_store_locked", "run-state SQLite store is locked", "retry_state_store");
  if (
    message.includes("not a database") ||
    message.includes("malformed") ||
    message.includes("corrupt")
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
  return failure("run_state_not_available", "run-state query is unavailable", "retry_state_store", {
    error: error instanceof Error ? error.message : String(error),
  });
}

async function openReadOnlyStore(
  workspaceRootAbs: string,
  projectName: string,
): Promise<
  | { ok: true; database: RunStateDatabase; databasePathAbs: string; close: () => void }
  | QueryFailure
> {
  const rootAbs = path.resolve(workspaceRootAbs, ".mcpjvm");
  const projectDirAbs = path.resolve(rootAbs, projectName);
  const databasePathAbs = path.join(projectDirAbs, "run-state.sqlite");
  const databaseExists = await fs
    .stat(databasePathAbs)
    .then(() => true)
    .catch(() => false);
  const markerExists = await fs
    .stat(cutoverMarkerPath(databasePathAbs))
    .then(() => true)
    .catch(() => false);
  const sentinelExists = await fs
    .stat(cutoverSentinelPath(workspaceRootAbs, projectName))
    .then(() => true)
    .catch(() => false);
  if (!databaseExists) {
    return markerExists || sentinelExists
      ? failure(
          "state_store_required_after_cutover",
          "the SQLite state store is required after cutover",
          "repair_state_store",
          { databasePathAbs },
        )
      : failure(
          "run_state_store_not_ready",
          "run-state SQLite store is not initialized or cut over",
          "run_state_store_cutover",
        );
  }
  let database: RunStateDatabase;
  try {
    database = new DatabaseSync(databasePathAbs, { readOnly: true });
  } catch (error) {
    return classify(error);
  }
  try {
    const cutover = database
      .prepare("SELECT status FROM state_store_cutover WHERE project_name = ?")
      .get(projectName);
    if (cutover?.status !== "cutover_complete") {
      database.close();
      return failure(
        "run_state_store_not_ready",
        "run-state SQLite store is not cut over",
        "run_state_store_cutover",
        { status: cutover?.status ?? "pre_cutover" },
      );
    }
    return { ok: true, database, databasePathAbs, close: () => database.close() };
  } catch (error) {
    database.close();
    return classify(error);
  }
}

export async function openReadOnlyRunStateStore(args: {
  workspaceRootAbs: string;
  projectName: string;
}): Promise<
  | { ok: true; database: RunStateDatabase; databasePathAbs: string; close: () => void }
  | QueryFailure
> {
  return openReadOnlyStore(args.workspaceRootAbs, args.projectName);
}

export async function queryRunState(args: {
  workspaceRootAbs: string;
  input: QueryInput;
}): Promise<QuerySuccess | QueryFailure> {
  const input = args.input;
  const direction = input.sortDirection ?? "desc";
  const pageSize = input.pageSize ?? DEFAULT_PAGE_SIZE;
  if (pageSize < 1 || pageSize > MAX_PAGE_SIZE)
    return failure(
      "run_state_query_invalid",
      "pageSize must be between 1 and 100",
      "correct_state_store_input",
    );
  if (
    input.startedFromEpochMs !== undefined &&
    input.startedToEpochMs !== undefined &&
    input.startedFromEpochMs > input.startedToEpochMs
  )
    return failure(
      "run_state_query_invalid",
      "started time range is contradictory",
      "correct_state_store_input",
    );
  if (
    input.completedFromEpochMs !== undefined &&
    input.completedToEpochMs !== undefined &&
    input.completedFromEpochMs > input.completedToEpochMs
  )
    return failure(
      "run_state_query_invalid",
      "completed time range is contradictory",
      "correct_state_store_input",
    );
  const statuses =
    input.status === undefined ? [] : Array.isArray(input.status) ? input.status : [input.status];
  if (statuses.some((status) => !STATUS_VALUES.has(status)))
    return failure(
      "run_state_query_invalid",
      "status contains an unknown value",
      "correct_state_store_input",
    );
  const queryFingerprint = fingerprint(input);
  if (input.cursor) {
    const cursor = decodeCursor(input.cursor, queryFingerprint, direction);
    if ("reasonCode" in cursor) return cursor;
  }
  const store = await openReadOnlyStore(args.workspaceRootAbs, input.projectName);
  if (!store.ok) return store;
  try {
    const clauses: string[] = [];
    const parameters: unknown[] = [input.projectName, input.projectName];
    if (input.planName) clauses.push("state_kind = 'plan' AND plan_name = ?");
    if (input.planName) parameters.push(input.planName);
    if (input.runId) clauses.push("state_kind = 'plan' AND run_id = ?");
    if (input.runId) parameters.push(input.runId);
    if (input.suiteRunId) clauses.push("suite_run_id = ?");
    if (input.suiteRunId) parameters.push(input.suiteRunId);
    if (input.executionProfile) clauses.push("execution_profile = ?");
    if (input.executionProfile) parameters.push(input.executionProfile);
    if (input.activePhase) clauses.push("active_phase = ?");
    if (input.activePhase) parameters.push(input.activePhase);
    if (statuses.length) {
      clauses.push(`status IN (${statuses.map(() => "?").join(", ")})`);
      parameters.push(...statuses);
    }
    if (input.startedFromEpochMs !== undefined) {
      clauses.push("started_at_epoch_ms >= ?");
      parameters.push(input.startedFromEpochMs);
    }
    if (input.startedToEpochMs !== undefined) {
      clauses.push("started_at_epoch_ms <= ?");
      parameters.push(input.startedToEpochMs);
    }
    if (input.completedFromEpochMs !== undefined) {
      clauses.push("completed_at_epoch_ms >= ?");
      parameters.push(input.completedFromEpochMs);
    }
    if (input.completedToEpochMs !== undefined) {
      clauses.push("completed_at_epoch_ms <= ?");
      parameters.push(input.completedToEpochMs);
    }
    const cursorValue = input.cursor
      ? decodeCursor(input.cursor, queryFingerprint, direction)
      : undefined;
    if (cursorValue && !("reasonCode" in cursorValue)) {
      clauses.push(cursorPredicate(direction));
      parameters.push(...cursorParameters(cursorValue.last));
    }
    const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
    const order = direction === "desc" ? "DESC" : "ASC";
    const rows = store.database
      .prepare(
        `
      SELECT * FROM (
        SELECT 'suite' AS state_kind, project_name, '' AS plan_name, '' AS run_id,
          suite_run_id, execution_profile, status, active_plan_name, active_plan_order,
          active_phase, next_plan_order, started_at_epoch_ms, updated_at_epoch_ms,
          completed_at_epoch_ms, revision, (completed_at_epoch_ms IS NULL) AS resumable,
          reason_code, continuation_json
        FROM suite_runs WHERE project_name = ?
        UNION ALL
        SELECT 'plan' AS state_kind, plan_runs.project_name, plan_runs.plan_name, plan_runs.run_id,
          COALESCE(suite_runs.suite_run_id, ''), suite_runs.execution_profile, plan_runs.status, suite_runs.active_plan_name,
          plan_runs.plan_order, suite_runs.active_phase, NULL, plan_runs.started_at_epoch_ms, COALESCE(plan_runs.completed_at_epoch_ms, plan_runs.started_at_epoch_ms),
          plan_runs.completed_at_epoch_ms, plan_runs.revision, (plan_runs.status = 'in_progress') AS resumable,
          plan_runs.reason_code, NULL
        FROM plan_runs LEFT JOIN suite_runs ON suite_runs.suite_run_pk = plan_runs.suite_run_pk
        WHERE plan_runs.project_name = ?
      ) ${where}
      ORDER BY updated_at_epoch_ms ${order}, state_kind ${order}, project_name ${order}, plan_name ${order}, run_id ${order}, suite_run_id ${order}
      LIMIT ?
    `,
      )
      .all(...parameters, pageSize + 1);
    const limited = rows.slice(0, pageSize);
    const artifactStatement = store.database.prepare(`
      SELECT artifact_kind, path_rel, checksum
      FROM artifacts
      WHERE project_name = ? AND (
        (plan_name = ? AND run_id = ?) OR
        (suite_run_id = ?)
      )
      ORDER BY artifact_id ASC
    `);
    const items = limited.map((row) => ({
      stateKind: row.state_kind,
      projectName: row.project_name,
      ...(row.plan_name ? { planName: row.plan_name } : {}),
      ...(row.run_id ? { runId: row.run_id } : {}),
      ...(row.suite_run_id ? { suiteRunId: row.suite_run_id } : {}),
      ...(row.execution_profile ? { executionProfile: row.execution_profile } : {}),
      status: row.status,
      ...(row.active_plan_name ? { activePlan: row.active_plan_name } : {}),
      ...(row.active_plan_order !== null && row.active_plan_order !== undefined
        ? { activePlanOrder: Number(row.active_plan_order) }
        : {}),
      ...(row.active_phase ? { activePhase: row.active_phase } : {}),
      ...(row.next_plan_order !== null && row.next_plan_order !== undefined
        ? { nextPlanOrder: Number(row.next_plan_order) }
        : {}),
      startedAtEpochMs: Number(row.started_at_epoch_ms),
      updatedAtEpochMs: Number(row.updated_at_epoch_ms),
      ...(row.completed_at_epoch_ms !== null && row.completed_at_epoch_ms !== undefined
        ? { completedAtEpochMs: Number(row.completed_at_epoch_ms) }
        : {}),
      revision: Number(row.revision),
      resumable: Boolean(row.resumable),
      ...(row.reason_code ? { reasonCode: row.reason_code } : {}),
      artifactReferences: artifactStatement
        .all(row.project_name, row.plan_name, row.run_id, row.suite_run_id)
        .map((artifact) => ({
          artifactKind: artifact.artifact_kind,
          pathRel: artifact.path_rel,
          ...(artifact.checksum ? { checksum: artifact.checksum } : {}),
        })),
    }));
    const result: QuerySuccess = {
      ok: true,
      stateSurface: "run_state",
      projectName: input.projectName,
      projectionVersion: PROJECTION_VERSION,
      pageSize,
      sort: { field: "updatedAtEpochMs", direction },
      items,
    };
    if (rows.length > pageSize && limited.length > 0) {
      const last = limited.at(-1)!;
      result.nextCursor = encodeCursor({
        version: 1,
        projectionVersion: 1,
        queryFingerprint,
        sortDirection: direction,
        last: {
          updatedAtEpochMs: Number(last.updated_at_epoch_ms),
          stateKind: last.state_kind as "suite" | "plan",
          projectName: String(last.project_name),
          planName: String(last.plan_name),
          runId: String(last.run_id),
          suiteRunId: String(last.suite_run_id),
        },
      });
    }
    return result;
  } catch (error) {
    return classify(error);
  } finally {
    store.close();
  }
}

export type {
  QueryInput as RunStateQueryInput,
  QueryFailure as RunStateQueryFailure,
  QuerySuccess as RunStateQuerySuccess,
};
