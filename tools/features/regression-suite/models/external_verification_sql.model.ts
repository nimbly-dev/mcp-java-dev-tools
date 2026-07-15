import type {
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";

export type SqlExecutionFailureCode =
  | "external_verification_connection_unresolved"
  | "external_verification_connection_invalid"
  | "external_verification_connection_unsupported"
  | "external_verification_execution_failed"
  | "external_verification_expectation_failed"
  | "external_verification_response_invalid"
  | "external_verification_request_unresolved"
  | "extract_path_missing";

export type SqlProviderExecutionResult = {
  result: NormalizedExternalVerificationResult;
  resolvedContext: Record<string, unknown>;
};

export type SqliteConnectionConfig = {
  kind: "sqlite";
  filePath: string;
};

export type PostgresqlConnectionConfig = {
  kind: "postgresql";
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  tlsMode: "disable" | "require" | "verify-full";
};

export type SqlBindingValue = string | number | bigint | Uint8Array | null;

export type SqlBindings = {
  byName: Record<string, SqlBindingValue>;
  ordered: Array<{ name: string; value: SqlBindingValue }>;
};

export type SqlWorkerResult =
  | { ok: true; rows: unknown[]; durationMs: number }
  | { ok: false; errorMessage: string; durationMs: number };

export type SqlExecutionSuccess = { ok: true; rows: unknown[]; durationMs: number };
export type SqlExecutionFailure = { ok: false; errorMessage: string; durationMs: number };
export type SqlExecutionResult = SqlExecutionSuccess | SqlExecutionFailure;

export type ExecuteSqlExternalVerificationArgs = {
  verification: PlanExternalVerification;
  resolvedContext: Record<string, unknown>;
  workspaceRootAbs: string;
  internals?: {
    executePostgresqlQuery?: (args: {
      connection: PostgresqlConnectionConfig;
      statement: string;
      bindings: SqlBindings["ordered"];
      timeoutMs?: number | null;
    }) => Promise<SqlExecutionResult>;
  };
};
