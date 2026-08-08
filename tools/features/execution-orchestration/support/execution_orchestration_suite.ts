import { dispatchPerformanceSuiteAction } from "@tools-feature-performance-suite";
import { dispatchRegressionSuiteAction } from "@tools-regression-suite";
import { dispatchSecuritySuiteAction } from "@tools-feature-security-suite";
import type {
  RuntimeSuiteBlockedResult,
  RuntimeSuiteRunResult,
} from "@tools-execution-plan-spec/models/runtime_suite.model";
import type {
  ExecutionOrchestrationPassState,
  ExecutionOrchestrationPassResult,
} from "../models/execution_orchestration.model";
import type { ExecutionOrchestrationSuiteToolInvoker } from "./execution_orchestration_transport";

export function createSuitePassExecutor(args: {
  suiteType: "performance" | "regression" | "security";
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  maxPlansPerPass: number | undefined;
  mcpInvoke: ExecutionOrchestrationSuiteToolInvoker;
  renewSuiteLease: (deadlineAtEpochMs?: number) => Promise<void>;
  runtimeLifecyclePrepared?: boolean;
  signal?: AbortSignal;
}): (
  state: ExecutionOrchestrationPassState,
  remainingBudgetMs: number,
) => Promise<ExecutionOrchestrationPassResult> {
  return async (state, remainingBudgetMs) => {
    const sharedInput = {
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      executionProfile: args.executionProfile,
      ...(typeof state.suiteRunId === "string" ? { suiteRunId: state.suiteRunId } : {}),
      ...(typeof args.maxPlansPerPass === "number"
        ? { maxPlansPerCall: args.maxPlansPerPass }
        : {}),
      ...(state.priorSuite && Array.isArray(state.priorSuite.planRuns)
        ? { priorPlanRuns: state.priorSuite.planRuns }
        : {}),
      ...(state.priorSuite && typeof state.priorSuite.suiteContext === "object"
        ? { priorSuiteContext: state.priorSuite.suiteContext }
        : {}),
      ...(state.priorSuite && typeof state.priorSuite.nextPlanOrder === "number"
        ? { startPlanOrder: state.priorSuite.nextPlanOrder }
        : {}),
      mcpInvoke: args.mcpInvoke,
      ...(args.runtimeLifecyclePrepared ? { runtimeLifecyclePrepared: true } : {}),
      ...(args.signal ? { signal: args.signal } : {}),
    };

    if (args.suiteType === "performance") {
      return dispatchPerformanceSuiteAction({
        action: "execute",
        input: sharedInput,
      }) as Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult>;
    }

    if (args.suiteType === "security") {
      return dispatchSecuritySuiteAction({
        action: "execute",
        input: sharedInput,
      }) as Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult>;
    }

    return dispatchRegressionSuiteAction({
      action: "execute_runtime_suite",
      input: {
        ...sharedInput,
        renewSuiteLease: args.renewSuiteLease,
        orchestrationTimeoutBudgetMs: remainingBudgetMs,
      },
    }) as Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult>;
  };
}
