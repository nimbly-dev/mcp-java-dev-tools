import type { RuntimeSuiteRunResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import { buildTimestampRunId } from "@tools-feature-regression-suite";
import type { ExecutePerformanceRuntimeSuiteArgs } from "../models/performance_suite.model";

import { executePerformancePlanWorkflow } from "../support/execute_performance_plan";
import { readPerformanceSuiteManifest } from "../support/load_performance_suite_manifest";
export async function executePerformanceRuntimeSuite(
  args: ExecutePerformanceRuntimeSuiteArgs,
): Promise<
  RuntimeSuiteRunResult | { status: "blocked"; reasonCode: string; requiredUserAction: string[] }
> {
  const suite = await readPerformanceSuiteManifest({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
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
  const suiteRunId =
    typeof args.suiteRunId === "string" && args.suiteRunId.trim().length > 0
      ? args.suiteRunId.trim()
      : buildTimestampRunId(new Date(), 1);
  const planRuns: RuntimeSuiteRunResult["planRuns"] = Array.isArray(args.priorPlanRuns)
    ? args.priorPlanRuns.map((entry) => ({ ...entry }))
    : [];
  let hasFail = planRuns.some((entry) => entry.status === "executed" && entry.runStatus === "fail");
  let hasBlocked = planRuns.some(
    (entry) =>
      entry.status === "blocked" || (entry.status === "executed" && entry.runStatus === "blocked"),
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
  let processedPlansThisCall = 0;
  let nextPlanOrder: number | undefined;
  let stop = false;

  for (const plan of orderedPlans) {
    if (plan.order < startPlanOrder) continue;
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
    const run = await executePerformancePlanWorkflow({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      planName: plan.planName,
      executionProfileName: manifest.executionProfile,
      suiteRunId,
      ...(manifest.runtimeContextName ? { runtimeContextName: manifest.runtimeContextName } : {}),
      ...(manifest.runtimeConfig ? { runtimeConfigOverride: manifest.runtimeConfig } : {}),
      ...(plan.providedContext ? { providedContext: plan.providedContext } : {}),
      mcpInvoke: args.mcpInvoke,
    });
    processedPlansThisCall += 1;
    if (run.status === "blocked") {
      hasBlocked = true;
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "blocked",
        blockedReasonCode: run.preflight.reasonCode,
        ...(typeof run.runId === "string" ? { runId: run.runId } : {}),
      });
      const effectiveOnFail =
        plan.onFail === "stop" || plan.onFail === "continue"
          ? plan.onFail
          : manifest.executionPolicy === "stop_on_fail"
            ? "stop"
            : "continue";
      if (effectiveOnFail === "stop") stop = true;
      continue;
    }
    planRuns.push({
      order: plan.order,
      planName: plan.planName,
      status: "executed",
      runStatus: run.runStatus,
      runId: run.runId,
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
    return {
      executionProfile: manifest.executionProfile,
      executionPolicy: manifest.executionPolicy,
      status: "in_progress",
      planRuns,
      suiteRunId,
      nextPlanOrder,
      completedPlanCount: planRuns.length,
    };
  }

  let status: RuntimeSuiteRunResult["status"] = "pass";
  if (manifest.executionPolicy === "continue_on_fail") {
    if (hasBlocked || hasFail) status = "partial_fail";
  } else if (hasBlocked) {
    status = "blocked";
  } else if (hasFail) {
    status = "fail";
  }
  return {
    executionProfile: manifest.executionProfile,
    executionPolicy: manifest.executionPolicy,
    status,
    planRuns,
    suiteRunId,
    completedPlanCount: planRuns.length,
  };
}
