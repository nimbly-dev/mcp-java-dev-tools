const assert = require("node:assert/strict");
const test = require("node:test");

const {
  EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS,
  executeExecutionOrchestrationResiliencyLoop,
  resolveExecutionOrchestrationLoopPolicy,
} = require("@tools-feature-execution-orchestration");

test("resolveExecutionOrchestrationLoopPolicy caps effective timeout budget below raw tool timeout", () => {
  const policy = resolveExecutionOrchestrationLoopPolicy({
    resumePollMax: 30,
    resumePollIntervalMs: 10000,
    resumePollTimeoutMs: 600000,
  });

  assert.equal(policy.timeoutInterceptMs, EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS);
  assert.equal(policy.effectiveTimeoutBudgetMs, EXECUTION_ORCHESTRATION_TIMEOUT_INTERCEPT_MS);
});

test("executeExecutionOrchestrationResiliencyLoop resumes persisted in_progress suiteRunId and nextPlanOrder", async () => {
  const persistedSuites = new Map<string, Record<string, unknown>>();
  const sleeps: number[] = [];
  const passStates: Array<{ suiteRunId: string | undefined; nextPlanOrder: unknown }> = [];

  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "watcher-suite",
    defaults: {
      resumePollMax: 3,
      resumePollIntervalMs: 25,
      resumePollTimeoutMs: 120000,
    },
    executePass: async (state: { suiteRunId?: string; priorSuite?: Record<string, unknown> | null }) => {
      passStates.push({
        suiteRunId: state.suiteRunId,
        nextPlanOrder: state.priorSuite?.nextPlanOrder,
      });
      if (passStates.length === 1) {
        return {
          executionProfile: "watcher-suite",
          executionPolicy: "stop_on_fail",
          status: "in_progress",
          suiteRunId: "suite-01",
          nextPlanOrder: 2,
          completedPlanCount: 1,
          planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
        };
      }
      return {
        executionProfile: "watcher-suite",
        executionPolicy: "stop_on_fail",
        status: "pass",
        suiteRunId: "suite-01",
        completedPlanCount: 2,
        planRuns: [
          { order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" },
          { order: 2, planName: "plan-b", status: "executed", runStatus: "pass", runId: "run-b" },
        ],
      };
    },
    persistSuite: async (suite: Record<string, unknown>) => {
      persistedSuites.set(String(suite.suiteRunId), suite);
    },
    readPersistedSuite: async (suiteRunId: string) => persistedSuites.get(suiteRunId) ?? null,
    sleepMs: async (ms: number) => {
      sleeps.push(ms);
    },
  });

  assert.equal(out.status, "pass");
  assert.deepEqual(sleeps, []);
  assert.equal(passStates.length, 2);
  assert.deepEqual(passStates[0], { suiteRunId: undefined, nextPlanOrder: undefined });
  assert.deepEqual(passStates[1], { suiteRunId: "suite-01", nextPlanOrder: 2 });
});

test("executeExecutionOrchestrationResiliencyLoop sleeps between passes when no new completed plan progress is available", async () => {
  const persistedSuites = new Map<string, Record<string, unknown>>();
  const sleeps: number[] = [];

  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "watcher-suite",
    defaults: {
      resumePollMax: 2,
      resumePollIntervalMs: 25,
      resumePollTimeoutMs: 120000,
    },
    initialSuiteRunId: "suite-01",
    initialPriorSuite: {
      executionProfile: "watcher-suite",
      executionPolicy: "stop_on_fail",
      status: "in_progress",
      suiteRunId: "suite-01",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    },
    executePass: async () => ({
      executionProfile: "watcher-suite",
      executionPolicy: "stop_on_fail",
      status: "in_progress",
      suiteRunId: "suite-01",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    }),
    persistSuite: async (suite: Record<string, unknown>) => {
      persistedSuites.set(String(suite.suiteRunId), suite);
    },
    readPersistedSuite: async (suiteRunId: string) => persistedSuites.get(suiteRunId) ?? null,
    sleepMs: async (ms: number) => {
      sleeps.push(ms);
      persistedSuites.set("suite-01", {
        executionProfile: "watcher-suite",
        executionPolicy: "stop_on_fail",
        status: "pass",
        suiteRunId: "suite-01",
        completedPlanCount: 2,
        planRuns: [
          { order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" },
          { order: 2, planName: "plan-b", status: "executed", runStatus: "pass", runId: "run-b" },
        ],
      });
    },
  });

  assert.equal(out.status, "pass");
  assert.deepEqual(sleeps, [25]);
});

test("executeExecutionOrchestrationResiliencyLoop returns blocked terminal timeout classification when timeout budget is exhausted before another pass", async () => {
  let nowMs = 0;
  let executeCalls = 0;

  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "long-suite",
    defaults: {
      resumePollMax: 5,
      resumePollIntervalMs: 100,
      resumePollTimeoutMs: 250,
    },
    executePass: async () => {
      executeCalls += 1;
      nowMs += 251;
      return {
        executionProfile: "long-suite",
        executionPolicy: "continue_on_fail",
        status: "in_progress",
        suiteRunId: "suite-time-budget",
        nextPlanOrder: 2,
        completedPlanCount: 1,
        planRuns: [{ order: 1, planName: "long-plan", status: "executed", runStatus: "pass", runId: "run-1" }],
      };
    },
    persistSuite: async () => {},
    readPersistedSuite: async () => null,
    sleepMs: async () => {
      nowMs += 100;
    },
    nowMs: () => nowMs,
  });

  assert.equal(out.status, "blocked");
  assert.equal(out.reasonCode, "orchestrator_timeout_budget_exhausted");
  assert.equal(executeCalls, 1);
});

test("executeExecutionOrchestrationResiliencyLoop returns blocked poll exhaustion when the outer pass limit is reached", async () => {
  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "long-suite",
    defaults: {
      resumePollMax: 1,
      resumePollIntervalMs: 100,
      resumePollTimeoutMs: 2000,
    },
    executePass: async () => ({
      executionProfile: "long-suite",
      executionPolicy: "continue_on_fail",
      status: "in_progress",
      suiteRunId: "suite-poll-limit",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "long-plan", status: "executed", runStatus: "pass", runId: "run-1" }],
    }),
    persistSuite: async () => {},
    readPersistedSuite: async () => null,
  });

  assert.equal(out.status, "blocked");
  assert.equal(out.reasonCode, "orchestrator_poll_limit_exhausted");
});

test("executeExecutionOrchestrationResiliencyLoop returns blocked progress-stalled classification when only sub-second budget remains", async () => {
  let nowMs = 0;
  const remainingBudgets: number[] = [];

  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "long-suite",
    defaults: {
      resumePollMax: 5,
      resumePollIntervalMs: 100,
      resumePollTimeoutMs: 2000,
    },
    initialSuiteRunId: "suite-time-budget",
    initialPriorSuite: {
      executionProfile: "long-suite",
      executionPolicy: "continue_on_fail",
      status: "in_progress",
      suiteRunId: "suite-time-budget",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "long-plan", status: "executed", runStatus: "pass", runId: "run-1" }],
    },
    executePass: async (_state: unknown, remainingBudgetMs: number) => {
      remainingBudgets.push(remainingBudgetMs);
      nowMs += 1_100;
      return {
        executionProfile: "long-suite",
        executionPolicy: "continue_on_fail",
        status: "in_progress",
        suiteRunId: "suite-time-budget",
        nextPlanOrder: 2,
        completedPlanCount: 1,
        planRuns: [{ order: 1, planName: "long-plan", status: "executed", runStatus: "pass", runId: "run-1" }],
      };
    },
    persistSuite: async () => {},
    readPersistedSuite: async () => ({
      executionProfile: "long-suite",
      executionPolicy: "continue_on_fail",
      status: "in_progress",
      suiteRunId: "suite-time-budget",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "long-plan", status: "executed", runStatus: "pass", runId: "run-1" }],
    }),
    nowMs: () => nowMs,
  });

  assert.equal(out.status, "blocked");
  assert.equal(out.reasonCode, "orchestrator_progress_stalled");
  assert.deepEqual(remainingBudgets, [2_000]);
});

test("executeExecutionOrchestrationResiliencyLoop fails closed when persisted suite progress is missing before resume", async () => {
  const out = await executeExecutionOrchestrationResiliencyLoop({
    projectName: "test-project",
    executionProfile: "watcher-suite",
    defaults: {
      resumePollMax: 2,
      resumePollIntervalMs: 25,
      resumePollTimeoutMs: 120000,
    },
    executePass: async () => ({
      executionProfile: "watcher-suite",
      executionPolicy: "stop_on_fail",
      status: "in_progress",
      suiteRunId: "suite-missing",
      nextPlanOrder: 2,
      completedPlanCount: 1,
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    }),
    persistSuite: async () => {},
    readPersistedSuite: async () => null,
    sleepMs: async () => {},
  });

  assert.equal(out.status, "blocked");
  assert.equal(out.reasonCode, "suite_progress_missing");
});
