import type { ProbeDomainConfig } from "@tools-feature-probe";
import type {
  RuntimeSuiteBlockedResult,
  RuntimeSuiteRunResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";

export type ExecutionOrchestrationLoopDefaults = {
  resumePollMax: number;
  resumePollIntervalMs: number;
  resumePollTimeoutMs: number;
};

export type ExecutionOrchestrationLoopPolicy = ExecutionOrchestrationLoopDefaults & {
  timeoutInterceptMs: number;
  effectiveTimeoutBudgetMs: number;
};

export type ExecutionOrchestrationPassState = {
  suiteRunId?: string;
  priorSuite?: RuntimeSuiteRunResult | null;
};

export type ExecutionOrchestrationActionInput = {
  workspaceRootAbs: string;
  action: "execute";
  probeConfig?: ProbeDomainConfig;
  payload: {
    projectName: string;
    executionProfile: string;
    suiteRunId?: string;
    maxPlansPerCall?: number;
  };
};

export type ExecutionOrchestrationActionResult = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type ExecutionOrchestrationPassResult = RuntimeSuiteRunResult | RuntimeSuiteBlockedResult;
