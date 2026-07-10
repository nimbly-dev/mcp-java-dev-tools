import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { DatabaseSync } from "node:sqlite";
import { Worker } from "node:worker_threads";

import type {
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import { validateNormalizedExternalVerificationResultShape } from "../../../spec/regression-execution-plan-spec/src/external_verification_contract.util";
import { JDBC_SQL_RUNNER_SOURCE } from "./external_verification_sql_jdbc_runner_source";
import { evaluateStepExpectations } from "../shared/regression_expectation_evaluator";
import { applyStepExtractWithDiagnostics } from "../../../spec/regression-execution-plan-spec/src/step_extract.util";

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

type JdbcConnectionConfig = {
  kind: "jdbc";
  connectionKind: string;
  jdbcUrl: string;
  driverClass?: string;
  classpathEntries: string[];
  properties: Record<string, string>;
  javaBin: string;
};

type SqlBindingValue = string | number | bigint | Uint8Array | null;
type SqlBindings = {
  byName: Record<string, SqlBindingValue>;
  ordered: Array<{
    name: string;
    value: SqlBindingValue;
  }>;
};

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

type SqlExecutionSuccess = {
  ok: true;
  rows: unknown[];
  durationMs: number;
};

type SqlExecutionFailure = {
  ok: false;
  errorMessage: string;
  durationMs: number;
};

type SqlExecutionResult = SqlExecutionSuccess | SqlExecutionFailure;

type ExecuteSqlExternalVerificationArgs = {
  verification: PlanExternalVerification;
  resolvedContext: Record<string, unknown>;
  workspaceRootAbs: string;
  internals?: {
    executeJdbcQuery?: (args: {
      connection: JdbcConnectionConfig;
      statement: string;
      bindings: SqlBindings["ordered"];
      timeoutMs?: number | null;
    }) => Promise<SqlExecutionResult>;
  };
};

let jdbcRunnerSourcePathCache: string | undefined;

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
        filePath: rawFilePath === ":memory:"
          ? rawFilePath
          : (path.isAbsolute(rawFilePath)
            ? rawFilePath
            : path.resolve(args.workspaceRootAbs, rawFilePath)),
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
      }).map((entry) => (path.isAbsolute(entry) ? entry : path.resolve(args.workspaceRootAbs, entry))),
      properties: collectStringEntriesByPrefix({
        prefix: `${prefix}.jdbc.properties.`,
        resolvedContext: args.resolvedContext,
      }),
      javaBin: typeof args.resolvedContext[`${prefix}.javaBin`] === "string" &&
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

function encodeBindingType(value: SqlBindingValue): string {
  if (value === null) {
    return "null";
  }
  if (typeof value === "string") {
    return "string";
  }
  if (typeof value === "number") {
    return "number";
  }
  if (typeof value === "bigint") {
    return "bigint";
  }
  return "bytes";
}

function encodeBindingValue(value: SqlBindingValue): string | undefined {
  if (value === null) {
    return undefined;
  }
  if (value instanceof Uint8Array) {
    return Buffer.from(value).toString("base64");
  }
  return Buffer.from(String(value), "utf8").toString("base64");
}

function ensureJdbcRunnerSourceFile(): string {
  if (jdbcRunnerSourcePathCache && fs.existsSync(jdbcRunnerSourcePathCache)) {
    return jdbcRunnerSourcePathCache;
  }
  const runnerDir = path.join(os.tmpdir(), "mcp-jvm-sql-runners");
  fs.mkdirSync(runnerDir, { recursive: true });
  const runnerPath = path.join(runnerDir, "JdbcSqlRunner.java");
  if (!fs.existsSync(runnerPath) || fs.readFileSync(runnerPath, "utf8") !== JDBC_SQL_RUNNER_SOURCE) {
    fs.writeFileSync(runnerPath, JDBC_SQL_RUNNER_SOURCE, "utf8");
  }
  jdbcRunnerSourcePathCache = runnerPath;
  return runnerPath;
}

function parseJdbcRunnerOutput(stdout: string): SqlExecutionResult {
  const trimmed = stdout.trim();
  if (trimmed.length === 0) {
    return {
      ok: false,
      errorMessage: "jdbc_runner_empty_output",
      durationMs: 0,
    };
  }

  const lines = trimmed.split(/\r?\n/);
  const header = lines[0]?.split("\t") ?? [];
  if (header[0] === "ERR") {
    return {
      ok: false,
      durationMs: Number(header[1] ?? "0") || 0,
      errorMessage: header[2] ? Buffer.from(header[2], "base64").toString("utf8") : "jdbc_runner_error",
    };
  }
  if (header[0] !== "OK") {
    return {
      ok: false,
      errorMessage: "jdbc_runner_output_invalid",
      durationMs: 0,
    };
  }

  const rowsByIndex = new Map<number, Record<string, unknown>>();
  for (const line of lines.slice(1)) {
    if (!line.startsWith("ROW\t")) {
      continue;
    }
    const parts = line.split("\t");
    const rowIndex = Number(parts[1] ?? "-1");
    const columnName = parts[2] ? Buffer.from(parts[2], "base64").toString("utf8") : "";
    const valueType = parts[3] ?? "string";
    const rawValue = parts[4] ?? "";
    if (!Number.isInteger(rowIndex) || rowIndex < 0 || columnName.length === 0) {
      return {
        ok: false,
        errorMessage: "jdbc_runner_row_invalid",
        durationMs: Number(header[1] ?? "0") || 0,
      };
    }
    const row = rowsByIndex.get(rowIndex) ?? {};
    if (valueType === "null") {
      row[columnName] = null;
    } else if (valueType === "bytes") {
      row[columnName] = Uint8Array.from(Buffer.from(rawValue, "base64"));
    } else if (valueType === "boolean") {
      row[columnName] = rawValue === "true";
    } else if (valueType === "integer" || valueType === "double") {
      const numeric = Number(rawValue);
      row[columnName] = Number.isFinite(numeric) ? numeric : rawValue;
    } else if (valueType === "decimal") {
      row[columnName] = rawValue;
    } else if (valueType === "bigint") {
      row[columnName] = rawValue;
    } else {
      row[columnName] = Buffer.from(rawValue, "base64").toString("utf8");
    }
    rowsByIndex.set(rowIndex, row);
  }

  return {
    ok: true,
    durationMs: Number(header[1] ?? "0") || 0,
    rows: Array.from(rowsByIndex.entries())
      .sort((a, b) => a[0] - b[0])
      .map((entry) => entry[1]),
  };
}

async function executeJdbcQuery(args: {
  connection: JdbcConnectionConfig;
  statement: string;
  bindings: SqlBindings["ordered"];
  timeoutMs?: number | null;
}): Promise<SqlExecutionResult> {
  const runnerPath = ensureJdbcRunnerSourceFile();
  const env: NodeJS.ProcessEnv = { ...process.env };
  env.SQL_JDBC_URL = args.connection.jdbcUrl;
  env.SQL_STATEMENT_B64 = Buffer.from(args.statement, "utf8").toString("base64");
  env.SQL_BINDING_COUNT = String(args.bindings.length);
  env.SQL_PROP_COUNT = String(Object.keys(args.connection.properties).length);
  if (args.connection.driverClass) {
    env.SQL_DRIVER_CLASS = args.connection.driverClass;
  }
  if (typeof args.timeoutMs === "number") {
    env.SQL_TIMEOUT_SECONDS = String(args.timeoutMs);
  }

  args.bindings.forEach((binding, index) => {
    env[`SQL_BINDING_${index}_NAME`] = binding.name;
    env[`SQL_BINDING_${index}_TYPE`] = encodeBindingType(binding.value);
    const encodedValue = encodeBindingValue(binding.value);
    if (encodedValue) {
      env[`SQL_BINDING_${index}_VALUE_B64`] = encodedValue;
    }
  });

  Object.entries(args.connection.properties).forEach(([key, value], index) => {
    env[`SQL_PROP_${index}_KEY`] = key;
    env[`SQL_PROP_${index}_VALUE_B64`] = Buffer.from(value, "utf8").toString("base64");
  });

  const launchArgs: string[] = [];
  if (args.connection.classpathEntries.length > 0) {
    launchArgs.push("--class-path", args.connection.classpathEntries.join(path.delimiter));
  }
  launchArgs.push(runnerPath);

  return await new Promise<SqlExecutionResult>((resolve) => {
    const child = spawn(args.connection.javaBin, launchArgs, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
      env,
    });

    let stdout = "";
    let stderr = "";
    let settled = false;
    let timeoutHandle: NodeJS.Timeout | undefined;

    const finish = (result: SqlExecutionResult) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timeoutHandle) {
        clearTimeout(timeoutHandle);
      }
      resolve(result);
    };

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });

    child.once("error", (error) => {
      finish({
        ok: false,
        errorMessage: `jdbc_runner_spawn_failed:${error instanceof Error ? error.message : String(error)}`,
        durationMs: 0,
      });
    });

    child.once("close", (code) => {
      if (code !== 0 && stdout.trim().length === 0) {
        finish({
          ok: false,
          errorMessage: stderr.trim().length > 0 ? stderr.trim() : `jdbc_runner_exit_${String(code)}`,
          durationMs: 0,
        });
        return;
      }
      const parsed = parseJdbcRunnerOutput(stdout);
      if (!parsed.ok && stderr.trim().length > 0) {
        finish({
          ok: false,
          errorMessage: `${parsed.errorMessage}; stderr=${stderr.trim()}`,
          durationMs: parsed.durationMs,
        });
        return;
      }
      finish(parsed);
    });

    if (typeof args.timeoutMs === "number") {
      const timeoutMs = args.timeoutMs;
      timeoutHandle = setTimeout(() => {
        finish({
          ok: false,
          errorMessage: `sql_execution_timeout_${String(timeoutMs)}ms`,
          durationMs: timeoutMs,
        });
        child.kill();
      }, timeoutMs);
    }
  });
}

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

export async function executeSqlExternalVerification(args: ExecuteSqlExternalVerificationArgs): Promise<SqlProviderExecutionResult> {
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

  const execution = connection.config.kind === "sqlite"
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
        ...(connection.config.kind === "jdbc" ? { connectionKind: connection.config.connectionKind } : {}),
        errorMessage: execution.errorMessage,
        ...(typeof request.timeoutMs === "number" && execution.errorMessage.startsWith("sql_execution_timeout_")
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
