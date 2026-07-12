import type {
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import { validateNormalizedExternalVerificationResultShape } from "../../../spec/regression-execution-plan-spec/src/external_verification_contract.util";
import { evaluateStepExpectations } from "../shared/regression_expectation_evaluator";
import { applyStepExtractWithDiagnostics } from "./regression_step_extract";
import type {
  ExecuteSqlExternalVerificationArgs,
  JdbcConnectionConfig,
  SqlBindingValue,
  SqlBindings,
  SqlExecutionFailureCode,
  SqlExecutionResult,
  SqlExecutionSuccess,
  SqlProviderExecutionResult,
  SqliteConnectionConfig,
  SqlWorkerResult,
} from "../models/external_verification_sql.model";
import path from "node:path";
import { Worker } from "node:worker_threads";
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
    status: (entry.status === "blocked_invalid" ? "blocked" : entry.status) as
      | "pass"
      | "fail"
      | "blocked",
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
      Object.entries(value as Record<string, unknown>).map(([key, entry]) => [
        key,
        normalizeSqlCellValue(entry),
      ]),
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

function collectStringEntriesByPrefix(args: {
  prefix: string;
  resolvedContext: Record<string, unknown>;
}): Record<string, string> {
  const entries: Record<string, string> = {};
  for (const [key, value] of Object.entries(args.resolvedContext)) {
    if (!key.startsWith(args.prefix)) {
      continue;
    }
    const suffix = key.slice(args.prefix.length);
    if (suffix.length === 0 || typeof value !== "string") {
      continue;
    }
    entries[suffix] = value;
  }
  return entries;
}

function resolveJdbcClasspathEntries(args: {
  connectionRef: string;
  resolvedContext: Record<string, unknown>;
  prefix: string;
}): string[] {
  const rawClasspath = args.resolvedContext[`${args.prefix}.jdbc.classpath`];
  if (Array.isArray(rawClasspath)) {
    return rawClasspath
      .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
      .map((entry) => entry.trim());
  }
  if (typeof rawClasspath === "string" && rawClasspath.trim().length > 0) {
    return rawClasspath
      .split(path.delimiter)
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0);
  }
  return [];
}

function resolveSqlConnectionConfig(args: {
  workspaceRootAbs: string;
  resolvedContext: Record<string, unknown>;
  connectionRef: string;
}):
  | { ok: true; config: SqliteConnectionConfig | JdbcConnectionConfig }
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

  if (kind === "sqlite") {
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
        filePath:
          rawFilePath === ":memory:"
            ? rawFilePath
            : path.isAbsolute(rawFilePath)
              ? rawFilePath
              : path.resolve(args.workspaceRootAbs, rawFilePath),
      },
    };
  }

  const jdbcUrl = args.resolvedContext[`${prefix}.jdbc.url`];
  if (typeof jdbcUrl !== "string" || jdbcUrl.trim().length === 0) {
    return {
      ok: false,
      reasonCode: "external_verification_connection_unresolved",
      reasonMeta: {
        connectionRef: args.connectionRef,
        connectionKind: kind,
        missingContextKey: `${prefix}.jdbc.url`,
      },
    };
  }

  return {
    ok: true,
    config: {
      kind: "jdbc",
      connectionKind: kind,
      jdbcUrl: jdbcUrl.trim(),
      ...(typeof args.resolvedContext[`${prefix}.jdbc.driverClass`] === "string" &&
      String(args.resolvedContext[`${prefix}.jdbc.driverClass`]).trim().length > 0
        ? { driverClass: String(args.resolvedContext[`${prefix}.jdbc.driverClass`]).trim() }
        : {}),
      classpathEntries: resolveJdbcClasspathEntries({
        connectionRef: args.connectionRef,
        resolvedContext: args.resolvedContext,
        prefix,
      }).map((entry) =>
        path.isAbsolute(entry) ? entry : path.resolve(args.workspaceRootAbs, entry),
      ),
      properties: collectStringEntriesByPrefix({
        prefix: `${prefix}.jdbc.properties.`,
        resolvedContext: args.resolvedContext,
      }),
      javaBin:
        typeof args.resolvedContext[`${prefix}.javaBin`] === "string" &&
        String(args.resolvedContext[`${prefix}.javaBin`]).trim().length > 0
          ? String(args.resolvedContext[`${prefix}.javaBin`]).trim()
          : "java",
    },
  };
}

function resolveSqlParameters(args: {
  verification: PlanExternalVerification;
  resolvedContext: Record<string, unknown>;
}):
  | { ok: true; bindings: SqlBindings }
  | { ok: false; reasonCode: SqlExecutionFailureCode; reasonMeta: Record<string, unknown> } {
  const parameters = args.verification.request.sql?.parameters ?? [];
  const byName: Record<string, SqlBindingValue> = {};
  const ordered: SqlBindings["ordered"] = [];
  for (const parameter of parameters) {
    let resolvedValue: SqlBindingValue;
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
      resolvedValue = value;
    } else {
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
      resolvedValue = value;
    }
    byName[parameter.name] = resolvedValue;
    ordered.push({
      name: parameter.name,
      value: resolvedValue,
    });
  }
  return {
    ok: true,
    bindings: {
      byName,
      ordered,
    },
  };
}

function executeSqliteQueryWithTimeout(args: {
  filePath: string;
  statement: string;
  bindings: Record<string, SqlBindingValue>;
  timeoutMs?: number | null;
}): Promise<SqlExecutionResult> {
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

  return new Promise<SqlExecutionResult>((resolve) => {
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

import { executeJdbcQuery } from "./external_verification_jdbc_runner";

function buildSqlExecutionEnvelope(args: {
  verification: PlanExternalVerification;
  execution: SqlExecutionSuccess;
  resolvedContext: Record<string, unknown>;
}): SqlProviderExecutionResult {
  const rows = normalizeSqlRows(args.execution.rows);
  const durationMs = args.execution.durationMs;
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
      extract: extractOutcome.outcomes.filter(
        (entry) => entry.required && entry.status === "unresolved",
      ),
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
    ...(args.verification.request.sql?.connectionRef
      ? { connectionRef: args.verification.request.sql.connectionRef }
      : {}),
    requestSummary: {
      connectionRef: args.verification.request.sql?.connectionRef,
      ...(typeof args.verification.request.sql?.timeoutMs === "number"
        ? { timeoutMs: args.verification.request.sql.timeoutMs }
        : {}),
    },
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

export async function executeSqlExternalVerification(
  args: ExecuteSqlExternalVerificationArgs,
): Promise<SqlProviderExecutionResult> {
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

  const execution =
    connection.config.kind === "sqlite"
      ? await executeSqliteQueryWithTimeout({
          filePath: connection.config.filePath,
          statement: request.statement,
          bindings: parameters.bindings.byName,
          ...(typeof request.timeoutMs === "undefined" ? {} : { timeoutMs: request.timeoutMs }),
        })
      : await (args.internals?.executeJdbcQuery ?? executeJdbcQuery)({
          connection: connection.config,
          statement: request.statement,
          bindings: parameters.bindings.ordered,
          ...(typeof request.timeoutMs === "undefined" ? {} : { timeoutMs: request.timeoutMs }),
        });

  if (!execution.ok) {
    return buildSqlFailureResult({
      verification: args.verification,
      reasonCode: "external_verification_execution_failed",
      reasonMeta: {
        connectionRef: request.connectionRef,
        ...(connection.config.kind === "jdbc"
          ? { connectionKind: connection.config.connectionKind }
          : {}),
        errorMessage: execution.errorMessage,
        ...(typeof request.timeoutMs === "number" &&
        execution.errorMessage.startsWith("sql_execution_timeout_")
          ? { timeoutMs: request.timeoutMs }
          : {}),
      },
    });
  }
  return buildSqlExecutionEnvelope({
    verification: args.verification,
    execution,
    resolvedContext: args.resolvedContext,
  });
}
