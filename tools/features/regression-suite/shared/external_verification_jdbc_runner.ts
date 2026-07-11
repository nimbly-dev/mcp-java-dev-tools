/**
 * JDBC runner process support for SQL external verification.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

import { JDBC_SQL_RUNNER_SOURCE } from "./external_verification_sql_jdbc_runner_source";
import type {
  JdbcConnectionConfig,
  SqlBindingValue,
  SqlBindings,
  SqlExecutionResult,
} from "../models/external_verification_sql.model";
let jdbcRunnerSourcePathCache: string | undefined;

export function encodeBindingType(value: SqlBindingValue): string {
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

export function encodeBindingValue(value: SqlBindingValue): string | undefined {
  if (value === null) {
    return undefined;
  }
  if (value instanceof Uint8Array) {
    return Buffer.from(value).toString("base64");
  }
  return Buffer.from(String(value), "utf8").toString("base64");
}

export function ensureJdbcRunnerSourceFile(): string {
  if (jdbcRunnerSourcePathCache && fs.existsSync(jdbcRunnerSourcePathCache)) {
    return jdbcRunnerSourcePathCache;
  }
  const runnerDir = path.join(os.tmpdir(), "mcp-jvm-sql-runners");
  fs.mkdirSync(runnerDir, { recursive: true });
  const runnerPath = path.join(runnerDir, "JdbcSqlRunner.java");
  if (
    !fs.existsSync(runnerPath) ||
    fs.readFileSync(runnerPath, "utf8") !== JDBC_SQL_RUNNER_SOURCE
  ) {
    fs.writeFileSync(runnerPath, JDBC_SQL_RUNNER_SOURCE, "utf8");
  }
  jdbcRunnerSourcePathCache = runnerPath;
  return runnerPath;
}

export function parseJdbcRunnerOutput(stdout: string): SqlExecutionResult {
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
      errorMessage: header[2]
        ? Buffer.from(header[2], "base64").toString("utf8")
        : "jdbc_runner_error",
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

export async function executeJdbcQuery(args: {
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
          errorMessage:
            stderr.trim().length > 0 ? stderr.trim() : `jdbc_runner_exit_${String(code)}`,
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
