export type RuntimeSuiteExecutionPolicy = "stop_on_fail" | "continue_on_fail";
export type RuntimeSuitePlanOnFail = "inherit" | "stop" | "continue";

export type RuntimeSuiteRuntimeConfig = {
  requestTimeoutMs?: number;
  retryMax?: number;
};

export type RuntimeSuitePlanEntry = {
  order: number;
  planName: string;
  onFail?: RuntimeSuitePlanOnFail;
  runtimeContextName?: string;
  providedContext?: Record<string, unknown>;
};

export type RuntimeSuiteManifest = {
  executionProfile: string;
  runtimeContextName?: string;
  executionPolicy: RuntimeSuiteExecutionPolicy;
  runtimeConfig?: RuntimeSuiteRuntimeConfig;
  plans: RuntimeSuitePlanEntry[];
};

export type RuntimeSuitePlanRunResult = {
  order: number;
  planName: string;
  status: "executed" | "blocked" | "skipped";
  runStatus?: "pass" | "fail" | "blocked";
  blockedReasonCode?: string;
  runId?: string;
};

export type RuntimeSuiteRunStatus = "pass" | "fail" | "blocked" | "partial_fail";

export type RuntimeSuiteSessionExportResult =
  | {
      status: "written";
      sessionId: string;
      sessionDirAbs: string;
      manifestPathAbs: string;
    }
  | {
      status: "blocked";
      reasonCode: "session_export_write_failed";
      requiredUserAction: string[];
    };

export type RuntimeSuiteRunResult = {
  executionProfile: string;
  status: RuntimeSuiteRunStatus;
  executionPolicy: RuntimeSuiteExecutionPolicy;
  planRuns: RuntimeSuitePlanRunResult[];
  sessionExport?: RuntimeSuiteSessionExportResult;
};
