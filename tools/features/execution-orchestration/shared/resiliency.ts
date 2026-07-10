import type {
  RuntimeSuiteBlockedResult,
  RuntimeSuiteRunResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import type {
  ExecutionOrchestrationLoopDefaults,
  ExecutionOrchestrationLoopPolicy,
  ExecutionOrchestrationPassState,
} from "../models/execution_orchestration.model";

const RAW_TOOL_TIMEOUT_MS = 300_000;
const RAW_TOOL_TIMEOUT_HEADROOM_MS = 15_000;
const MIN_EXECUTION_PASS_BUDGET_MS = 1_000;

export const EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS = RAW_TOOL_TIMEOUT_MS - RAW_TOOL_TIMEOUT_HEADROOM_MS;

export type { ExecutionOrchestrationLoopDefaults, ExecutionOrchestrationLoopPolicy } from "../models/execution_orchestration.model";

function withTerminalReason(args: {
  suite: RuntimeSuiteRunResult;
  reasonCode: string;
  reasonMeta?: Record<string, unknown>;
}): RuntimeSuiteRunResult {
  const { nextPlanOrder: _nextPlanOrder, ...suiteWithoutNextPlanOrder } = args.suite;
  return {
    ...suiteWithoutNextPlanOrder,
    status: "blocked",
    reasonCode: args.reasonCode,
    ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
    ...(args.suite.progressSummary
      ? {
          progressSummary: {
            ...args.suite.progressSummary,
            progressState: "terminal",
          },
        }
      : {}),
  };
}

async function persistTerminalSuite(args: {
  persistSuite: (suite: RuntimeSuiteRunResult) => Promise<void>;
  suite: RuntimeSuiteRunResult;
  reasonCode: string;
  reasonMeta?: Record<string, unknown>;
}): Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult> {
  const suiteRunId = args.suite.suiteRunId?.trim();
  if (!suiteRunId) {
    return buildBlockedResult({
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [
        "Persist a non-empty suiteRunId before bounded execution_orchestration resume.",
      ],
    });
  }
  const normalizedSuite =
    suiteRunId === args.suite.suiteRunId
      ? args.suite
      : {
          ...args.suite,
          suiteRunId,
        };
  const terminalSuite = withTerminalReason({
    suite: normalizedSuite,
    reasonCode: args.reasonCode,
    ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
  });
  await args.persistSuite(terminalSuite);
  return terminalSuite;
}

function buildBlockedResult(args: {
  reasonCode: string;
  requiredUserAction: string[];
}): RuntimeSuiteBlockedResult {
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
    state: ExecutionOrchestrationPassState,
    remainingBudgetMs: number,
  ) => Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult>;
  persistSuite: (suite: RuntimeSuiteRunResult) => Promise<void>;
  readPersistedSuite: (suiteRunId: string) => Promise<RuntimeSuiteRunResult | null>;
  sleepMs?: (ms: number) => Promise<void>;
  nowMs?: () => number;
}): Promise<RuntimeSuiteRunResult | RuntimeSuiteBlockedResult> {
  const nowMs = args.nowMs ?? (() => Date.now());
  const sleepMs = args.sleepMs ?? (async (ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const policy = resolveExecutionOrchestrationLoopPolicy(args.defaults);
  const minExecutionPassBudgetMs = Math.min(MIN_EXECUTION_PASS_BUDGET_MS, policy.effectiveTimeoutBudgetMs);
  const startedAtMs = nowMs();

  let state: ExecutionOrchestrationPassState = {
    ...(typeof args.initialSuiteRunId === "string" ? { suiteRunId: args.initialSuiteRunId } : {}),
    ...(args.initialPriorSuite ? { priorSuite: args.initialPriorSuite } : {}),
  };
  let latestInProgressSuite: RuntimeSuiteRunResult | null = null;
  let noProgressOuterCycleCount = 0;

  for (let passIndex = 0; passIndex < policy.resumePollMax; passIndex += 1) {
    const elapsedBeforePassMs = nowMs() - startedAtMs;
    const remainingBudgetBeforePassMs = policy.effectiveTimeoutBudgetMs - elapsedBeforePassMs;
    if (remainingBudgetBeforePassMs < minExecutionPassBudgetMs) {
      if (latestInProgressSuite) {
        return await persistTerminalSuite({
          persistSuite: args.persistSuite,
          suite: latestInProgressSuite,
          reasonCode: "orchestrator_timeout_budget_exhausted",
          reasonMeta: {
            resumePollTimeoutMs: policy.resumePollTimeoutMs,
            effectiveTimeoutBudgetMs: policy.effectiveTimeoutBudgetMs,
            elapsedMs: elapsedBeforePassMs,
          },
        });
      }
      return buildBlockedResult({
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
      return await persistTerminalSuite({
        persistSuite: args.persistSuite,
        suite,
        reasonCode: "orchestrator_poll_limit_exhausted",
        reasonMeta: {
          resumePollMax: policy.resumePollMax,
          completedPassCount: passIndex + 1,
        },
      });
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
    const observedNoProgressOuterCycleCount = progressAdvanced ? 0 : noProgressOuterCycleCount + 1;
    const requiredRemainingBudgetMs = progressAdvanced
      ? minExecutionPassBudgetMs
      : policy.resumePollIntervalMs + minExecutionPassBudgetMs;
    if (remainingBudgetMs < requiredRemainingBudgetMs) {
      return await persistTerminalSuite({
        persistSuite: args.persistSuite,
        suite,
        reasonCode: progressAdvanced
          ? "orchestrator_timeout_budget_exhausted"
          : "orchestrator_progress_stalled",
        reasonMeta: progressAdvanced
          ? {
              resumePollTimeoutMs: policy.resumePollTimeoutMs,
              effectiveTimeoutBudgetMs: policy.effectiveTimeoutBudgetMs,
              elapsedMs,
            }
          : {
              resumePollIntervalMs: policy.resumePollIntervalMs,
              remainingBudgetMs,
              completedPlanCount: currentCompletedPlanCount,
              noProgressOuterCycleCount: observedNoProgressOuterCycleCount,
            },
      });
    }

    if (progressAdvanced) {
      noProgressOuterCycleCount = 0;
    } else {
      noProgressOuterCycleCount = observedNoProgressOuterCycleCount;
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
