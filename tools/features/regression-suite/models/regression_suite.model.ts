import type { RegressionRunExecutionResult, RegressionRunArtifactsWriteResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";

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
};
