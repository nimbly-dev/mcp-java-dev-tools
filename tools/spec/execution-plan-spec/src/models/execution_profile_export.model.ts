export type ExecutionProfileExportPlanRun = {
  order: number;
  planName: string;
  status: "executed" | "blocked" | "skipped";
  runStatus?: "pass" | "fail" | "blocked";
  blockedReasonCode?: string;
  runId?: string;
};

export type ExecutionProfileSuiteType = "regression" | "performance" | "security";

export type ExecutionProfileExportManifest = {
  schemaVersion: "1.0.0";
  exportId: string;
  generatedAt: string;
  startedAt: string;
  endedAt: string;
  suiteType?: ExecutionProfileSuiteType;
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  runStatus: "pass" | "fail" | "blocked" | "partial_fail";
  replayPackageType?: "request_replay_only" | "workload_replay_only";
  runtimeContextName?: string;
  runtimeConfig?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  planRuns: ExecutionProfileExportPlanRun[];
};

export type WriteExecutionProfileExportInput = {
  workspaceRootAbs: string;
  exportId: string;
  generatedAt: Date;
  startedAt: Date;
  endedAt: Date;
  suiteType?: ExecutionProfileSuiteType;
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  runStatus: "pass" | "fail" | "blocked" | "partial_fail";
  replayPackageType?: "request_replay_only" | "workload_replay_only";
  runtimeContextName?: string;
  runtimeConfig?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  planRuns: ExecutionProfileExportPlanRun[];
};

export type WriteExecutionProfileExportResult = {
  exportId: string;
  manifest: ExecutionProfileExportManifest;
};
