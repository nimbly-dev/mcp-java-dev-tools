export type RunSessionExportPlanRun = {
  order: number;
  planName: string;
  status: "executed" | "blocked" | "skipped";
  runStatus?: "pass" | "fail" | "blocked";
  blockedReasonCode?: string;
  runId?: string;
};

export type RunSessionExportManifest = {
  schemaVersion: "1.0.0";
  sessionId: string;
  generatedAt: string;
  startedAt: string;
  endedAt: string;
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  runStatus: "pass" | "fail" | "blocked" | "partial_fail";
  runtimeContextName?: string;
  runtimeConfig?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  planRuns: RunSessionExportPlanRun[];
};

export type WriteRunSessionExportInput = {
  workspaceRootAbs: string;
  sessionId: string;
  generatedAt: Date;
  startedAt: Date;
  endedAt: Date;
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  runStatus: "pass" | "fail" | "blocked" | "partial_fail";
  runtimeContextName?: string;
  runtimeConfig?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  planRuns: RunSessionExportPlanRun[];
};

export type WriteRunSessionExportResult = {
  sessionId: string;
  sessionDirAbs: string;
  manifestPathAbs: string;
  manifest: RunSessionExportManifest;
};

export type ExportRunSessionPs1Input = {
  workspaceRootAbs: string;
  sessionId: string;
  includeResolvedSecrets?: boolean;
  includeRuntimeStartup?: boolean;
  includeHealthcheckGate?: boolean;
};

export type ExportRunSessionPs1Result = {
  sessionId: string;
  sessionDirAbs: string;
  manifestPathAbs: string;
  scriptPathAbs: string;
  readmePathAbs: string;
};
