import type {
  RegressionRunExecutionResult,
  RegressionRunArtifactsWriteResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";

export type RuntimeSuiteCorrelationEvent = {
  eventId: string;
  probeId: string;
  timestampEpochMs: number;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  lineKey?: string;
  eventType?: string;
};
export type CorrelationKeyType = "traceId" | "requestId" | "messageId";
export type CorrelationFailureReason =
  | "missing_correlation_key"
  | "window_exceeded"
  | "no_matching_events"
  | "ambiguous_correlation"
  | "flow_expectation_mismatch";
export type CorrelationInputEvent = {
  eventId: string;
  probeId: string;
  timestampEpochMs: number;
  keyType: CorrelationKeyType;
  keyValue?: string;
  lineKey?: string;
};
export type CorrelationPolicy = {
  keyType: CorrelationKeyType;
  keyValue: string;
  maxWindowMs: number;
  expectedFlow?: string[];
};
export type CorrelationMatchResult =
  | { status: "ok"; timeline: CorrelationInputEvent[]; reasonCode: "ok" }
  | {
      status: "fail_closed";
      timeline: CorrelationInputEvent[];
      reasonCode: CorrelationFailureReason;
    };

export type RuntimeSuiteCorrelationSession = {
  correlationSessionId: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  maxWindowMs: number;
  expectedFlow?: string[];
  contributingPlans: Set<string>;
  events: RuntimeSuiteCorrelationEvent[];
};

export type ConditionReasonCode =
  | "step_condition_malformed"
  | "step_condition_operator_invalid"
  | "step_condition_forward_reference"
  | "step_condition_path_missing"
  | "step_condition_type_mismatch";

export type CorrelationKeyResolution = {
  keyValue?: string;
  sourceType?: "header" | "json_path" | "capture_field";
  sourcePath?: string;
  reasonCode?: "correlation_key_extraction_failed";
};

export type RegressionCorrelationIndexEntry = {
  runId: string;
  planName: string;
  runPath: string;
  generatedAtEpochMs: number;
  status: "ok" | "fail_closed";
  reasonCode: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  correlationSessionId?: string;
  window: {
    startEpochMs?: number;
    endEpochMs?: number;
    maxWindowMs: number;
  };
  probeIds: string[];
};

export type RegressionMcpToolInvoker = (args: {
  toolName: string;
  input: Record<string, unknown>;
}) => Promise<{ structuredContent?: Record<string, unknown> }>;

export type RegressionPlanRuntimeConfigOverride = {
  requestTimeoutMs?: number;
  retryMax?: number;
};

export type ExecuteRegressionPlanWorkflowArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  planName: string;
  mcpInvoke: RegressionMcpToolInvoker;
  runId?: string;
  providedContext?: Record<string, unknown>;
  runtimeContextName?: string;
  executionProfileName?: string;
  suiteRunId?: string;
  runtimeConfigOverride?: RegressionPlanRuntimeConfigOverride;
  orchestrationTimeoutBudgetMs?: number;
  renewSuiteLease?: () => Promise<void>;
  resumeState?: {
    resolvedContext: Record<string, unknown>;
    executionResult: RegressionRunExecutionResult;
    evidence?: Record<string, unknown>;
  };
};

export type ExecuteRegressionPlanWorkflowResult =
  | {
      status: "blocked";
      preflight: { reasonCode: string; requiredUserAction: string[]; [key: string]: unknown };
    }
  | {
      status: "executed";
      runId: string;
      runStatus: "pass" | "fail" | "blocked" | "in_progress";
      artifacts: RegressionRunArtifactsWriteResult;
      executionResult: RegressionRunExecutionResult;
      suiteContext?: Record<string, unknown>;
    };

export type ExecuteRegressionRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  executionProfile: string;
  mcpInvoke: RegressionMcpToolInvoker;
  suiteRunId?: string;
  startPlanOrder?: number;
  priorPlanRuns?: RuntimeSuiteRunResult["planRuns"];
  priorSuiteContext?: Record<string, unknown>;
  maxPlansPerCall?: number;
  orchestrationTimeoutBudgetMs?: number;
  renewSuiteLease?: () => Promise<void>;
};
