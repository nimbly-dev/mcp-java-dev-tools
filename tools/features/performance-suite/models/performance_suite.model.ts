import type { RuntimeSuiteRunResult } from "@tools-execution-plan-spec/models/runtime_suite.model";

export type {
  PerformanceEntrypoint,
  PerformanceMstaConfigState,
  PerformancePlanContract,
  PerformancePlanMetadata,
  PerformanceMstaMethodStep,
  PerformanceMstaMethodSummary,
  PerformanceMstaSummary,
  PerformanceMstaTargetSummary,
  PersistedPerformanceMstaSummary,
} from "@tools-performance-execution-plan-spec/models/performance_execution_plan.model";

export type PerformanceMcpToolInvoker = (args: {
  toolName: string;
  input: Record<string, unknown>;
}) => Promise<{ structuredContent: Record<string, unknown> }>;

export type ExecutePerformancePlanWorkflowArgs = {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  executionProfileName: string;
  suiteRunId: string;
  runtimeContextName?: string;
  runtimeConfigOverride?: { requestTimeoutMs?: number; retryMax?: number };
  providedContext?: Record<string, unknown>;
  mcpInvoke: PerformanceMcpToolInvoker;
};

export type ExecutePerformanceRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  mcpInvoke: PerformanceMcpToolInvoker;
  suiteRunId?: string;
  startPlanOrder?: number;
  priorPlanRuns?: RuntimeSuiteRunResult["planRuns"];
  maxPlansPerCall?: number;
};
