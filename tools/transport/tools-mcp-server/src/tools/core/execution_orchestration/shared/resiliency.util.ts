import type { RuntimeSuiteRunResult } from "@tools-regression-execution-plan-spec/models/regression_runtime_suite.model";

const RAW_TOOL_TIMEOUT_MS = 300_000;
const RAW_TOOL_TIMEOUT_HEADROOM_MS = 15_000;
const MIN_EXECUTION_PASS_BUDGET_MS = 1_000;

export const EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS = RAW_TOOL_TIMEOUT_MS - RAW_TOOL_TIMEOUT_HEADROOM_MS;

export type ExecutionOrchestrationLoopDefaults = {
  resumePollMax: number;
  resumePollIntervalMs: number;
  resumePollTimeoutMs: number;
};

type SuiteBlockedResult = {
  status: "blocked";
  reasonCode: string;
  requiredUserAction: string[];
};

type SuitePassState = {
  suiteRunId?: string;
  priorSuite?: RuntimeSuiteRunResult | null;
};

export type ExecutionOrchestrationLoopPolicy = {
  resumePollMax: number;
  resumePollIntervalMs: number;
  resumePollTimeoutMs: number;
  timeoutInterceptMs: number;
  effectiveTimeoutBudgetMs: number;
};

function buildBlockedResult(args: {
  reasonCode: string;
  requiredUserAction: string[];
}): SuiteBlockedResult {
  return {
    status: "blocked",
    reasonCode: args.reasonCode,
    requiredUserAction: args.requiredUserAction,
  };
}

export function resolveExecutionOrchestrationLoopPolicy(
  defaults: ExecutionOrchestrationLoopDefaults,
): ExecutionOrchestrationLoopPolicy {
  return {
    resumePollMax: defaults.resumePollMax,
    resumePollIntervalMs: defaults.resumePollIntervalMs,
    resumePollTimeoutMs: defaults.resumePollTimeoutMs,
    timeoutInterceptMs: EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS,
    effectiveTimeoutBudgetMs: Math.min(
      defaults.resumePollTimeoutMs,
      EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS,
    ),
  };
}

export async function executeExecutionOrchestrationResiliencyLoop(args: {
  projectName: string;
  executionProfile: string;
  defaults: ExecutionOrchestrationLoopDefaults;
  initialSuiteRunId?: string;
  initialPriorSuite?: RuntimeSuiteRunResult | null;
  executePass: (
    state: SuitePassState,
    remainingBudgetMs: number,
  ) => Promise<RuntimeSuiteRunResult | SuiteBlockedResult>;
  persistSuite: (suite: RuntimeSuiteRunResult) => Promise<void>;
  readPersistedSuite: (suiteRunId: string) => Promise<RuntimeSuiteRunResult | null>;
  sleepMs?: (ms: number) => Promise<void>;
  nowMs?: () => number;
}): Promise<RuntimeSuiteRunResult | SuiteBlockedResult> {
  const nowMs = args.nowMs ?? (() => Date.now());
  const sleepMs = args.sleepMs ?? (async (ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const policy = resolveExecutionOrchestrationLoopPolicy(args.defaults);
  const minExecutionPassBudgetMs = Math.min(MIN_EXECUTION_PASS_BUDGET_MS, policy.effectiveTimeoutBudgetMs);
  const startedAtMs = nowMs();

  let state: SuitePassState = {
    ...(typeof args.initialSuiteRunId === "string" ? { suiteRunId: args.initialSuiteRunId } : {}),
    ...(args.initialPriorSuite ? { priorSuite: args.initialPriorSuite } : {}),
  };
  let latestInProgressSuite: RuntimeSuiteRunResult | null = null;

  for (let passIndex = 0; passIndex < policy.resumePollMax; passIndex += 1) {
    const elapsedBeforePassMs = nowMs() - startedAtMs;
    const remainingBudgetBeforePassMs = policy.effectiveTimeoutBudgetMs - elapsedBeforePassMs;
    if (remainingBudgetBeforePassMs < minExecutionPassBudgetMs) {
      return latestInProgressSuite ?? buildBlockedResult({
        reasonCode: "suite_progress_invalid",
        requiredUserAction: [
          `Increase orchestrator timeout budget before resuming project '${args.projectName}'.`,
        ],
      });
    }

    const suite = await args.executePass(state, remainingBudgetBeforePassMs);
    if (suite.status === "blocked") {
      return suite;
    }

    await args.persistSuite(suite);
    if (suite.status !== "in_progress") {
      return suite;
    }

    latestInProgressSuite = suite;
    if (passIndex + 1 >= policy.resumePollMax) {
      return suite;
    }

    const elapsedMs = nowMs() - startedAtMs;
    const remainingBudgetMs = policy.effectiveTimeoutBudgetMs - elapsedMs;
    const priorCompletedPlanCount =
      typeof state.priorSuite?.completedPlanCount === "number"
        ? state.priorSuite.completedPlanCount
        : Array.isArray(state.priorSuite?.planRuns)
          ? state.priorSuite.planRuns.length
          : 0;
    const currentCompletedPlanCount =
      typeof suite.completedPlanCount === "number"
        ? suite.completedPlanCount
        : Array.isArray(suite.planRuns)
          ? suite.planRuns.length
          : 0;
    const progressAdvanced = currentCompletedPlanCount > priorCompletedPlanCount;
    const requiredRemainingBudgetMs = progressAdvanced
      ? minExecutionPassBudgetMs
      : policy.resumePollIntervalMs + minExecutionPassBudgetMs;
    if (remainingBudgetMs < requiredRemainingBudgetMs) {
      return suite;
    }

    if (!progressAdvanced) {
      await sleepMs(policy.resumePollIntervalMs);
    }

    const suiteRunId = suite.suiteRunId?.trim();
    if (!suiteRunId) {
      return buildBlockedResult({
        reasonCode: "suite_progress_invalid",
        requiredUserAction: [
          "Persist a non-empty suiteRunId before bounded execution_orchestration resume.",
        ],
      });
    }

    const persisted = await args.readPersistedSuite(suiteRunId);
    if (!persisted) {
      return buildBlockedResult({
        reasonCode: "suite_progress_missing",
        requiredUserAction: [
          `Persist canonical suite progress before resuming suiteRunId '${suiteRunId}'.`,
        ],
      });
    }
    if (persisted.executionProfile !== args.executionProfile) {
      return buildBlockedResult({
        reasonCode: "suite_progress_mismatch",
        requiredUserAction: [
          `Persist executionProfile '${args.executionProfile}' for suiteRunId '${suiteRunId}'.`,
        ],
      });
    }
    if (persisted.status !== "in_progress") {
      return persisted;
    }
    if (typeof persisted.nextPlanOrder !== "number") {
      return buildBlockedResult({
        reasonCode: "suite_progress_invalid",
        requiredUserAction: [
          `Persist nextPlanOrder for in_progress suiteRunId '${suiteRunId}' before resuming execution.`,
        ],
      });
    }

    state = {
      suiteRunId,
      priorSuite: persisted,
    };
  }

  return (
    latestInProgressSuite ??
    buildBlockedResult({
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [
        `No resumable suite progress was produced for project '${args.projectName}'.`,
      ],
    })
  );
}
