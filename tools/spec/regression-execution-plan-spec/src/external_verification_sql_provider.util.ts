import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { Worker } from "node:worker_threads";

import type {
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { validateNormalizedExternalVerificationResultShape } from "@tools-regression-execution-plan-spec/external_verification_contract.util";
import { evaluateStepExpectations } from "@tools-regression-execution-plan-spec/regression_expectation_evaluator.util";
import { applyStepExtractWithDiagnostics } from "@tools-regression-execution-plan-spec/step_extract.util";

type SqlExecutionFailureCode =
  | "external_verification_connection_unresolved"
  | "external_verification_connection_invalid"
  | "external_verification_execution_failed"
  | "external_verification_expectation_failed"
  | "external_verification_response_invalid"
  | "external_verification_request_unresolved"
  | "extract_path_missing";

type SqlProviderExecutionResult = {
  result: NormalizedExternalVerificationResult;
  resolvedContext: Record<string, unknown>;
};

type SqliteConnectionConfig = {
  kind: "sqlite";
  filePath: string;
};

type SqlBindingValue = string | number | bigint | Uint8Array | null;

type SqlWorkerResult =
  | {
      ok: true;
      rows: unknown[];
      durationMs: number;
    }
  | {
      ok: false;
      errorMessage: string;
      durationMs: number;
    };

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function buildExtractedContext(args: {
  resolvedContext: Record<string, unknown>;
  extractMappings: PlanExternalVerification["extract"];
}): Record<string, unknown> | undefined {
  if (!args.extractMappings?.length) {
    return undefined;
  }
  const extractedContext: Record<string, unknown> = {};
  for (const mapping of args.extractMappings) {
    if (Object.prototype.hasOwnProperty.call(args.resolvedContext, mapping.as)) {
      extractedContext[mapping.as] = args.resolvedContext[mapping.as];
    }
  }
  return Object.keys(extractedContext).length > 0 ? extractedContext : undefined;
}

function normalizeExtractResults(
  extractResults: ReturnType<typeof applyStepExtractWithDiagnostics>["outcomes"],
  resolvedContext: Record<string, unknown>,
) {
  return extractResults.map((entry) => ({
    from: entry.from,
    as: entry.as,
    required: entry.required,
    status: entry.status,
    ...(entry.status === "resolved" ? { value: resolvedContext[entry.as] } : {}),
    ...(entry.status === "unresolved" ? { reasonCode: "extract_path_missing" as const } : {}),
  }));
}

function normalizeAssertionResults(
  assertions: ReturnType<typeof evaluateStepExpectations>["assertions"],
) {
  return assertions.map((entry) => ({
    id: entry.id,
    actualPath: entry.actualPath,
    operator: entry.operator,
    status: (
      entry.status === "blocked_invalid"
        ? "blocked"
        : entry.status
    ) as "pass" | "fail" | "blocked",
    ...(typeof entry.expected === "undefined" ? {} : { expected: entry.expected }),
    ...(typeof entry.actual === "undefined" ? {} : { actual: entry.actual }),
    ...(typeof entry.message === "undefined" ? {} : { message: entry.message }),
    ...(typeof entry.reasonCode === "undefined" ? {} : { reasonCode: entry.reasonCode }),
  }));
}

function buildSqlFailureResult(args: {
  verification: PlanExternalVerification;
  reasonCode: SqlExecutionFailureCode;
  reasonMeta?: Record<string, unknown>;
}): SqlProviderExecutionResult {
  return {
    result: {
      id: args.verification.id,
      providerType: "sql",
      status: "blocked_runtime",
      sql: {
        rowCount: 0,
        rows: [],
        durationMs: 1,
      },
      reasonCode: args.reasonCode,
      ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
    },
    resolvedContext: {},
  };
}

function normalizeSqlCellValue(value: unknown): unknown {
  if (typeof value === "bigint") {
    const numeric = Number(value);
    return Number.isSafeInteger(numeric) ? numeric : value.toString();
  }
  if (Array.isArray(value)) {
    return value.map((entry) => normalizeSqlCellValue(entry));
  }
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, entry]) => [key, normalizeSqlCellValue(entry)]),
    );
  }
  return value;
}

function normalizeSqlRows(rows: unknown[]): Record<string, unknown>[] {
  return rows
    .map((row) => asRecord(row))
    .filter((row): row is Record<string, unknown> => row !== null)
    .map((row) =>
      Object.fromEntries(
        Object.entries(row).map(([key, value]) => [key, normalizeSqlCellValue(value)]),
      ),
    );
}

function resolveSqlConnectionConfig(args: {
  workspaceRootAbs: string;
  resolvedContext: Record<string, unknown>;
  connectionRef: string;
}):
  | { ok: true; config: SqliteConnectionConfig }
  | { ok: false; reasonCode: SqlExecutionFailureCode; reasonMeta: Record<string, unknown> } {
  const prefix = `sql.connection.${args.connectionRef}`;
  const kind = args.resolvedContext[`${prefix}.kind`];
  if (typeof kind !== "string" || kind.trim().length === 0) {
    return {
      ok: false,
      reasonCode: "external_verification_connection_unresolved",
      reasonMeta: {
        connectionRef: args.connectionRef,
        missingContextKey: `${prefix}.kind`,
      },
    };
  }

  if (kind !== "sqlite") {
    return {
      ok: false,
      reasonCode: "external_verification_connection_invalid",
      reasonMeta: {
        connectionRef: args.connectionRef,
        connectionKind: kind,
        cause: "sql_connection_kind_not_supported",
      },
    };
  }

  const rawFilePath = args.resolvedContext[`${prefix}.sqlite.filePath`];
  if (typeof rawFilePath !== "string" || rawFilePath.trim().length === 0) {
    return {
      ok: false,
      reasonCode: "external_verification_connection_unresolved",
      reasonMeta: {
        connectionRef: args.connectionRef,
        missingContextKey: `${prefix}.sqlite.filePath`,
      },
    };
  }

  return {
    ok: true,
    config: {
      kind: "sqlite",
      filePath: rawFilePath === ":memory:"
        ? rawFilePath
        : (path.isAbsolute(rawFilePath)
          ? rawFilePath
          : path.resolve(args.workspaceRootAbs, rawFilePath)),
    },
  };
}

function resolveSqlParameters(args: {
  verification: PlanExternalVerification;
  resolvedContext: Record<string, unknown>;
}):
  | { ok: true; bindings: Record<string, SqlBindingValue> }
  | { ok: false; reasonCode: SqlExecutionFailureCode; reasonMeta: Record<string, unknown> } {
  const parameters = args.verification.request.sql?.parameters ?? [];
  const bindings: Record<string, SqlBindingValue> = {};
  for (const parameter of parameters) {
    if (typeof parameter.valueFromContext === "string") {
      if (!Object.prototype.hasOwnProperty.call(args.resolvedContext, parameter.valueFromContext)) {
        return {
          ok: false,
          reasonCode: "external_verification_request_unresolved",
          reasonMeta: {
            parameterName: parameter.name,
            missingContextKey: parameter.valueFromContext,
          },
        };
      }
      const value = args.resolvedContext[parameter.valueFromContext];
      if (
        value !== null &&
        typeof value !== "string" &&
        typeof value !== "number" &&
        typeof value !== "bigint" &&
        !(value instanceof Uint8Array)
      ) {
        return {
          ok: false,
          reasonCode: "external_verification_request_unresolved",
          reasonMeta: {
            parameterName: parameter.name,
            contextKey: parameter.valueFromContext,
            cause: "sql_parameter_value_type_invalid",
          },
        };
      }
      bindings[parameter.name] = value;
      continue;
    }
    const value = typeof parameter.value === "undefined" ? null : parameter.value;
    if (
      value !== null &&
      typeof value !== "string" &&
      typeof value !== "number" &&
      typeof value !== "bigint" &&
      !(value instanceof Uint8Array)
    ) {
      return {
        ok: false,
        reasonCode: "external_verification_request_unresolved",
        reasonMeta: {
          parameterName: parameter.name,
          cause: "sql_parameter_value_type_invalid",
        },
      };
    }
    bindings[parameter.name] = value;
  }
  return {
    ok: true,
    bindings,
  };
}

function executeSqliteQueryWithTimeout(args: {
  filePath: string;
  statement: string;
  bindings: Record<string, SqlBindingValue>;
  timeoutMs?: number | null;
}): Promise<SqlWorkerResult> {
  const workerSource = `
    const { parentPort, workerData } = require("node:worker_threads");
    const { DatabaseSync } = require("node:sqlite");
    const startedAt = Date.now();
    try {
      const db = new DatabaseSync(workerData.filePath);
      try {
        if (typeof workerData.timeoutMs === "number") {
          db.exec("PRAGMA busy_timeout = " + String(Math.floor(workerData.timeoutMs)));
        }
        const statement = db.prepare(workerData.statement);
        statement.setAllowBareNamedParameters(true);
        const rows = statement.all(workerData.bindings);
        parentPort.postMessage({
          ok: true,
          rows,
          durationMs: Date.now() - startedAt,
        });
      } finally {
        db.close();
      }
    } catch (error) {
      parentPort.postMessage({
        ok: false,
        errorMessage: error instanceof Error ? error.message : String(error),
        durationMs: Date.now() - startedAt,
      });
    }
  `;

  return new Promise<SqlWorkerResult>((resolve) => {
    const worker = new Worker(workerSource, {
      eval: true,
      workerData: {
        filePath: args.filePath,
        statement: args.statement,
        bindings: args.bindings,
        timeoutMs: args.timeoutMs ?? null,
      },
    });
    let settled = false;
    let timeoutHandle: NodeJS.Timeout | undefined;

    const finish = (result: SqlWorkerResult) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timeoutHandle) {
        clearTimeout(timeoutHandle);
      }
      resolve(result);
    };

    worker.once("message", (message: SqlWorkerResult) => {
      finish(message);
      void worker.terminate();
    });
    worker.once("error", (error) => {
      finish({
        ok: false,
        errorMessage: error instanceof Error ? error.message : String(error),
        durationMs: 0,
      });
      void worker.terminate();
    });
    worker.once("exit", (code) => {
      if (!settled && code !== 0) {
        finish({
          ok: false,
          errorMessage: `sql_worker_exit_${String(code)}`,
          durationMs: 0,
        });
      }
    });

    if (typeof args.timeoutMs === "number") {
      const timeoutMs = args.timeoutMs;
      timeoutHandle = setTimeout(() => {
        finish({
          ok: false,
          errorMessage: `sql_execution_timeout_${String(timeoutMs)}ms`,
          durationMs: timeoutMs,
        });
        void worker.terminate();
      }, timeoutMs);
    }
  });
}

export async function executeSqlExternalVerification(args: {
  verification: PlanExternalVerification;
  resolvedContext: Record<string, unknown>;
  workspaceRootAbs: string;
}): Promise<SqlProviderExecutionResult> {
  const request = args.verification.request.sql;
  if (!request) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: "external_verification_connection_invalid",
      reasonMeta: {
        cause: "sql_request_missing",
      },
    });
  }

  const connection = resolveSqlConnectionConfig({
    workspaceRootAbs: args.workspaceRootAbs,
    resolvedContext: args.resolvedContext,
    connectionRef: request.connectionRef,
  });
  if (!connection.ok) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: connection.reasonCode,
      reasonMeta: connection.reasonMeta,
    });
  }

  const parameters = resolveSqlParameters({
    verification: args.verification,
    resolvedContext: args.resolvedContext,
  });
  if (!parameters.ok) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: parameters.reasonCode,
      reasonMeta: parameters.reasonMeta,
    });
  }

  const execution = await executeSqliteQueryWithTimeout({
    filePath: connection.config.filePath,
    statement: request.statement,
    bindings: parameters.bindings,
    ...(typeof request.timeoutMs === "undefined" ? {} : { timeoutMs: request.timeoutMs }),
  });
  if (!execution.ok) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: "external_verification_execution_failed",
      reasonMeta: {
        connectionRef: request.connectionRef,
        errorMessage: execution.errorMessage,
        ...(typeof request.timeoutMs === "number" && execution.errorMessage.startsWith("sql_execution_timeout_")
          ? { timeoutMs: request.timeoutMs }
          : {}),
      },
    });
  }

  const rows = normalizeSqlRows(execution.rows);
  const durationMs = execution.durationMs;
  const envelope: Record<string, unknown> = {
    status: "pass",
    sql: {
      rowCount: rows.length,
      rows,
      ...(rows[0] ? { firstRow: rows[0] } : {}),
      durationMs,
    },
  };
  const evaluation = evaluateStepExpectations({
    stepResult: envelope,
    expectations: args.verification.expect,
    transportFailure: false,
    dependencyBlocked: false,
  });
  const extractOutcome = applyStepExtractWithDiagnostics(
    envelope,
    args.verification.extract,
    args.resolvedContext,
  );
  const nextResolvedContext = extractOutcome.context;
  const extractedContext = buildExtractedContext({
    resolvedContext: nextResolvedContext,
    extractMappings: args.verification.extract,
  });
  const extractResults = normalizeExtractResults(extractOutcome.outcomes, nextResolvedContext);
  const assertionResults = normalizeAssertionResults(evaluation.assertions);

  let reasonCode: SqlExecutionFailureCode | undefined;
  let reasonMeta: Record<string, unknown> | undefined;
  if (evaluation.status === "blocked_runtime") {
    reasonCode = "external_verification_response_invalid";
    reasonMeta = {
      assertionReasons: evaluation.assertions
        .filter((entry) => entry.status === "blocked_invalid")
        .map((entry) => ({
          id: entry.id,
          actualPath: entry.actualPath,
          reasonCode: entry.reasonCode,
        })),
    };
  } else if (evaluation.status === "fail_assertion") {
    reasonCode = "external_verification_expectation_failed";
  }

  if (extractOutcome.hasRequiredUnresolved) {
    reasonCode = "extract_path_missing";
    reasonMeta = {
      ...(reasonMeta ?? {}),
      extract: extractOutcome.outcomes.filter((entry) => entry.required && entry.status === "unresolved"),
    };
  }

  let status: NormalizedExternalVerificationResult["status"] = "pass";
  if (extractOutcome.hasRequiredUnresolved || evaluation.status === "blocked_runtime") {
    status = "blocked_runtime";
  } else if (evaluation.status === "fail_assertion") {
    status = "fail_assertion";
  }

  const result: NormalizedExternalVerificationResult = {
    id: args.verification.id,
    providerType: "sql",
    status,
    sql: {
      rowCount: rows.length,
      rows,
      ...(rows[0] ? { firstRow: rows[0] } : {}),
      durationMs,
    },
    ...(extractResults.length > 0 ? { extractResults } : {}),
    ...(assertionResults.length > 0 ? { assertions: assertionResults } : {}),
    ...(extractedContext ? { extractedContext } : {}),
    ...(reasonCode ? { reasonCode } : {}),
    ...(reasonMeta && Object.keys(reasonMeta).length > 0 ? { reasonMeta } : {}),
  };

  const validation = validateNormalizedExternalVerificationResultShape(result);
  if (!validation.ok) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: "external_verification_response_invalid",
      reasonMeta: {
        validationReasonCode: validation.reasonCode,
      },
    });
  }

  return {
    result,
    resolvedContext: nextResolvedContext,
  };
}
