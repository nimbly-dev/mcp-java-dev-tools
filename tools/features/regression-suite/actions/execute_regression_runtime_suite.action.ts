import type { ExecuteRegressionRuntimeSuiteArgs } from "../models/regression_suite.model";
import path from "node:path";
import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import type { RegressionRunExecutionResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { executeRegressionPlanWorkflow } from "./execute_regression_plan.action";
import { buildTimestampRunId } from "../support/regression_plan_execution";
import {
  buildRuntimeSuiteProgressSummary,
  countCompletedPlanRuns,
  isSuiteLevelPreflightBlocker,
  readPersistedRegressionPlanState,
  resolveBlockedPlanDetail,
  upsertPlanRun,
  isRecord,
  readPlanContract,
} from "../support/regression_suite_state";
import { validateSuiteContextDependencies } from "../support/regression_plan_preflight_validation";
import { readSuiteManifest } from "../support/load_regression_suite_manifest";
import {
  collectSuiteCorrelationSession,
  writeSuiteCorrelationResults,
} from "../support/regression_suite_correlation";
import type { RuntimeSuiteCorrelationSession } from "../support/regression_suite_correlation";

export type { ExecuteRegressionRuntimeSuiteArgs } from "../models/regression_suite.model";
export async function executeRegressionRuntimeSuite(
  args: ExecuteRegressionRuntimeSuiteArgs,
): Promise<
  RuntimeSuiteRunResult | { status: "blocked"; reasonCode: string; requiredUserAction: string[] }
> {
  const suite = await readSuiteManifest({
    workspaceRootAbs: args.workspaceRootAbs,
    ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
      ? { projectName: args.projectName.trim() }
      : {}),
    executionProfile: args.executionProfile,
  });
  if (!suite.ok) {
    return {
      status: "blocked",
      reasonCode: suite.reasonCode,
      requiredUserAction: suite.requiredUserAction,
    };
  }
  const manifest = suite.manifest;
  const planRuns: RuntimeSuiteRunResult["planRuns"] = Array.isArray(args.priorPlanRuns)
    ? args.priorPlanRuns.map((entry) => ({ ...entry }))
    : [];
  const suiteRunId =
    typeof args.suiteRunId === "string" && args.suiteRunId.trim().length > 0
      ? args.suiteRunId.trim()
      : buildTimestampRunId(new Date(), 1);
  const correlationSessions = new Map<string, RuntimeSuiteCorrelationSession>();
  const suiteProvidedContext: Record<string, unknown> = isRecord(args.priorSuiteContext)
    ? { ...args.priorSuiteContext }
    : {};
  let hasFail = planRuns.some((entry) => entry.status === "executed" && entry.runStatus === "fail");
  let hasBlocked = planRuns.some(
    (entry) =>
      entry.status === "blocked" || (entry.status === "executed" && entry.runStatus === "blocked"),
  );
  let suiteLevelBlocked = planRuns.some(
    (entry) => entry.status === "blocked" && isSuiteLevelPreflightBlocker(entry.blockedReasonCode),
  );
  const orderedPlans = [...manifest.plans].sort((a, b) => a.order - b.order);
  const startPlanOrder =
    typeof args.startPlanOrder === "number" &&
    Number.isInteger(args.startPlanOrder) &&
    args.startPlanOrder > 0
      ? args.startPlanOrder
      : 1;
  if (startPlanOrder > orderedPlans.length + 1) {
    return {
      status: "blocked",
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [
        `Persist nextPlanOrder within 1..${orderedPlans.length + 1} before resuming suite execution.`,
      ],
    };
  }
  const maxPlansPerCall =
    typeof args.maxPlansPerCall === "number" &&
    Number.isInteger(args.maxPlansPerCall) &&
    args.maxPlansPerCall > 0
      ? args.maxPlansPerCall
      : undefined;
  const plansRootAbs = await resolveRegressionPlansRootAbs(
    args.workspaceRootAbs,
    typeof args.projectName === "string" && args.projectName.trim().length > 0
      ? args.projectName.trim()
      : undefined,
  );
  const suiteContracts = await Promise.all(
    orderedPlans.map(async (plan) => ({
      planName: plan.planName,
      contract: await readPlanContract({
        workspaceRootAbs: args.workspaceRootAbs,
        ...(typeof args.projectName === "string" ? { projectName: args.projectName } : {}),
        planName: plan.planName,
      }),
      providedContextKeys: Object.keys(plan.providedContext ?? {}),
    })),
  );
  if (suiteContracts.some((entry) => !entry.contract)) {
    return {
      status: "blocked",
      reasonCode: "suite_contract_missing",
      requiredUserAction: ["Ensure every execution profile plan has a readable contract.json."],
    };
  }
  const suiteContextValidation = validateSuiteContextDependencies({
    plans: suiteContracts.flatMap((entry) =>
      entry.contract
        ? [
            {
              planName: entry.planName,
              contract: entry.contract,
              providedContextKeys: entry.providedContextKeys,
            },
          ]
        : [],
    ),
  });
  if (!suiteContextValidation.ok) {
    return {
      status: "blocked",
      reasonCode: suiteContextValidation.reasonCode,
      requiredUserAction: suiteContextValidation.requiredUserAction,
    };
  }
  for (const priorPlanRun of planRuns) {
    if (
      priorPlanRun.status !== "executed" ||
      priorPlanRun.runStatus === "in_progress" ||
      typeof priorPlanRun.runId !== "string"
    ) {
      continue;
    }
    const runDirAbs = path.join(plansRootAbs, priorPlanRun.planName, "runs", priorPlanRun.runId);
    await collectSuiteCorrelationSession({
      runDirAbs,
      planName: priorPlanRun.planName,
      sessions: correlationSessions,
      suiteContext: suiteProvidedContext,
    });
  }
  const suiteCallStartedAtMs = Date.now();
  let processedPlansThisCall = 0;
  let nextPlanOrder: number | undefined;
  let stop = false;
  let activeInProgressExecutionResult: RegressionRunExecutionResult | undefined;
  for (const plan of orderedPlans) {
    if (plan.order < startPlanOrder) {
      continue;
    }
    if (stop) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    if (typeof maxPlansPerCall === "number" && processedPlansThisCall >= maxPlansPerCall) {
      nextPlanOrder = plan.order;
      break;
    }
    const remainingOrchestrationBudgetMs =
      typeof args.orchestrationTimeoutBudgetMs === "number"
        ? args.orchestrationTimeoutBudgetMs - (Date.now() - suiteCallStartedAtMs)
        : undefined;
    const priorPlanRun = planRuns.find((entry) => entry.order === plan.order);
    const resumeState =
      priorPlanRun?.runStatus === "in_progress" && typeof priorPlanRun.runId === "string"
        ? await readPersistedRegressionPlanState({
            workspaceRootAbs: args.workspaceRootAbs,
            ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
              ? { projectName: args.projectName.trim() }
              : {}),
            planName: plan.planName,
            runId: priorPlanRun.runId,
          })
        : null;
    if (
      priorPlanRun?.runStatus === "in_progress" &&
      typeof priorPlanRun.runId === "string" &&
      !resumeState
    ) {
      return {
        status: "blocked",
        reasonCode: "suite_progress_missing",
        requiredUserAction: [
          `Persist run Artifacts for in_progress plan '${plan.planName}' before resuming suiteRunId '${suiteRunId}'.`,
        ],
      };
    }
    const run = await executeRegressionPlanWorkflow({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      planName: plan.planName,
      mcpInvoke: args.mcpInvoke,
      ...(typeof priorPlanRun?.runId === "string" ? { runId: priorPlanRun.runId } : {}),
      ...(manifest.runtimeConfig ? { runtimeConfigOverride: manifest.runtimeConfig } : {}),
      ...(plan.runtimeContextName || manifest.runtimeContextName
        ? { runtimeContextName: plan.runtimeContextName ?? manifest.runtimeContextName }
        : {}),
      executionProfileName: manifest.executionProfile,
      suiteRunId,
      providedContext: {
        ...(plan.providedContext ?? {}),
        ...suiteProvidedContext,
      },
      ...(typeof remainingOrchestrationBudgetMs === "number"
        ? { orchestrationTimeoutBudgetMs: remainingOrchestrationBudgetMs }
        : {}),
      ...(resumeState ? { resumeState } : {}),
      ...(args.renewSuiteLease ? { renewSuiteLease: args.renewSuiteLease } : {}),
    });
    if (run.status === "blocked") {
      processedPlansThisCall += 1;
      hasBlocked = true;
      if (isSuiteLevelPreflightBlocker(run.preflight.reasonCode)) {
        suiteLevelBlocked = true;
      }
      upsertPlanRun(planRuns, {
        order: plan.order,
        planName: plan.planName,
        status: "blocked",
        blockedReasonCode: run.preflight.reasonCode,
      });
      const effectiveOnFail =
        plan.onFail === "stop" || plan.onFail === "continue"
          ? plan.onFail
          : manifest.executionPolicy === "stop_on_fail"
            ? "stop"
            : "continue";
      if (suiteLevelBlocked || effectiveOnFail === "stop") stop = true;
      continue;
    }
    processedPlansThisCall += 1;
    upsertPlanRun(planRuns, {
      order: plan.order,
      planName: plan.planName,
      status: "executed",
      runStatus: run.runStatus,
      ...(run.runStatus === "blocked"
        ? resolveBlockedPlanDetail(run.executionResult as unknown as Record<string, unknown>)
        : {}),
      runId: run.runId,
    });
    if (run.runStatus === "in_progress") {
      nextPlanOrder = plan.order;
      activeInProgressExecutionResult = run.executionResult;
      break;
    }
    if (run.suiteContext) {
      Object.assign(suiteProvidedContext, run.suiteContext);
    }
    await collectSuiteCorrelationSession({
      runDirAbs: run.artifacts.runDirAbs,
      planName: plan.planName,
      sessions: correlationSessions,
      suiteContext: suiteProvidedContext,
    });
    if (run.runStatus === "fail") hasFail = true;
    if (run.runStatus === "blocked") hasBlocked = true;
    const effectiveOnFail =
      plan.onFail === "stop" || plan.onFail === "continue"
        ? plan.onFail
        : manifest.executionPolicy === "stop_on_fail"
          ? "stop"
          : "continue";
    if ((run.runStatus === "fail" || run.runStatus === "blocked") && effectiveOnFail === "stop") {
      stop = true;
    }
  }

  if (typeof nextPlanOrder === "number" && processedPlansThisCall === 0) {
    return {
      status: "blocked",
      reasonCode: "suite_progress_stalled",
      requiredUserAction: [
        "Advance at least one plan per resumed call or increase maxPlansPerCall.",
      ],
    };
  }

  if (typeof nextPlanOrder === "number") {
    const progressSummary = await buildRuntimeSuiteProgressSummary({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      manifest,
      status: "in_progress",
      planRuns,
      ...(activeInProgressExecutionResult
        ? { activeExecutionResult: activeInProgressExecutionResult }
        : {}),
    });
    return {
      executionProfile: manifest.executionProfile,
      executionPolicy: manifest.executionPolicy,
      status: "in_progress",
      planRuns,
      suiteRunId,
      nextPlanOrder,
      completedPlanCount: countCompletedPlanRuns(planRuns),
      ...(Object.keys(suiteProvidedContext).length > 0
        ? { suiteContext: suiteProvidedContext }
        : {}),
      progressSummary,
    };
  }

  let status: RuntimeSuiteRunResult["status"] = "pass";
  if (suiteLevelBlocked) {
    status = "blocked";
  } else if (manifest.executionPolicy === "continue_on_fail") {
    if (hasBlocked || hasFail) {
      status = "partial_fail";
    }
  } else if (hasBlocked) {
    status = "blocked";
  } else if (hasFail) {
    status = "fail";
  }

  const result: RuntimeSuiteRunResult = {
    executionProfile: manifest.executionProfile,
    executionPolicy: manifest.executionPolicy,
    status,
    planRuns,
    suiteRunId,
    completedPlanCount: countCompletedPlanRuns(planRuns),
    progressSummary: await buildRuntimeSuiteProgressSummary({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      manifest,
      status,
      planRuns,
    }),
  };
  const correlations = await writeSuiteCorrelationResults({
    sessions: correlationSessions,
    now: new Date(),
  });
  if (correlations.length > 0) {
    result.correlations = correlations;
  }
  if (Object.keys(suiteProvidedContext).length > 0) {
    result.suiteContext = suiteProvidedContext;
  }
  return result;
}
