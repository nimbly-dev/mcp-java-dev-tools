import fs from "node:fs/promises";
import path from "node:path";

import type {
  RuntimeSuitePlanRunResult,
  RuntimeSuiteProgressSummary,
  RuntimeSuiteRunResult,
} from "@tools-execution-plan-spec/models/runtime_suite.model";
import {
  resolveSecurityPlanRootAbs,
  validateSecurityPlanContract,
} from "@tools-security-execution-plan-spec";

import type {
  ExecuteSecurityRuntimeSuiteArgs,
  SecurityModeExecutionResult,
} from "../models/security_suite.model";
import { executeBlackboxSecurityMode } from "../modes/blackbox/security_blackbox_mode";
import { executeSidecarAssistedSecurityMode } from "../modes/sidecar_assisted/security_sidecar_assisted_mode";
import { readSecuritySuiteManifest } from "../support/load_security_suite_manifest";

type SecurityPlanExecution = {
  status: "executed" | "blocked";
  runStatus: "pass" | "fail" | "blocked";
  runId?: string;
  reasonCode?: string;
  requiredUserAction?: string[];
  reasonMeta: Record<string, unknown> & {
    securityMode: "blackbox" | "sidecar_assisted";
    planName: string;
  };
};

function buildSuiteRunId(args: ExecuteSecurityRuntimeSuiteArgs): string {
  const existing = args.suiteRunId?.trim();
  return existing || `security-${Date.now()}`;
}

function buildProgressSummary(args: {
  totalPlanCount: number;
  planRuns: RuntimeSuitePlanRunResult[];
  activePlan?: RuntimeSuitePlanRunResult;
}): RuntimeSuiteProgressSummary {
  const completedPlanCount = args.planRuns.filter((plan) => plan.status !== "skipped").length;
  const activePlan = args.activePlan;
  return {
    progressState: activePlan ? "ready_for_next_plan" : "terminal",
    totalPlanCount: args.totalPlanCount,
    completedPlanCount,
    remainingPlanCount: Math.max(args.totalPlanCount - completedPlanCount, 0),
    ...(activePlan
      ? {
          lastCompletedPlan: {
            order: activePlan.order,
            planName: activePlan.planName,
            status: activePlan.status === "blocked" ? "blocked" : "executed",
            ...(activePlan.runStatus && activePlan.runStatus !== "in_progress"
              ? { runStatus: activePlan.runStatus }
              : {}),
            ...(activePlan.runId ? { runId: activePlan.runId } : {}),
          },
        }
      : {}),
  };
}

function upsertPlanRun(
  planRuns: RuntimeSuitePlanRunResult[],
  next: RuntimeSuitePlanRunResult,
): void {
  const index = planRuns.findIndex((entry) => entry.order === next.order);
  if (index >= 0) {
    planRuns[index] = next;
    return;
  }
  planRuns.push(next);
  planRuns.sort((left, right) => left.order - right.order);
}

function effectiveOnFail(args: {
  plan: { onFail?: "inherit" | "stop" | "continue" };
  executionPolicy: "stop_on_fail" | "continue_on_fail";
}): "stop" | "continue" {
  if (args.plan.onFail === "stop" || args.plan.onFail === "continue") return args.plan.onFail;
  return args.executionPolicy === "stop_on_fail" ? "stop" : "continue";
}

function resolveSecuritySuiteStatus(args: {
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  hasBlocked: boolean;
  hasFail: boolean;
}): RuntimeSuiteRunResult["status"] {
  if (args.executionPolicy === "continue_on_fail") {
    return args.hasBlocked || args.hasFail ? "partial_fail" : "pass";
  }
  if (args.hasBlocked) return "blocked";
  if (args.hasFail) return "fail";
  return "pass";
}

function blockedSuiteResult(args: {
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  suiteRunId: string;
  reasonCode: string;
  requiredUserAction: string[];
}): RuntimeSuiteRunResult {
  return {
    executionProfile: args.executionProfile,
    executionPolicy: args.executionPolicy,
    status: "blocked",
    reasonCode: args.reasonCode,
    reasonMeta: { requiredUserAction: args.requiredUserAction },
    planRuns: [],
    suiteRunId: args.suiteRunId,
    completedPlanCount: 0,
    progressSummary: buildProgressSummary({ totalPlanCount: 0, planRuns: [] }),
  };
}

async function executeSecurityPlan(args: {
  input: ExecuteSecurityRuntimeSuiteArgs;
  planName: string;
}): Promise<SecurityPlanExecution> {
  let planRootAbs: string;
  try {
    planRootAbs = await resolveSecurityPlanRootAbs({
      workspaceRootAbs: args.input.workspaceRootAbs,
      projectName: args.input.projectName,
      planName: args.planName,
    });
  } catch (error) {
    const reasonCode = error instanceof Error ? error.message : String(error);
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode,
      requiredUserAction: [`Resolve the Security plan Artifact path for '${args.planName}'.`],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }

  const contractPath = path.join(planRootAbs, "contract.json");
  const rawContract = await fs.readFile(contractPath, "utf8").catch(() => undefined);
  if (!rawContract) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "security_contract_missing",
      requiredUserAction: [`Create security contract Artifact at ${contractPath}.`],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  let contract: unknown;
  try {
    contract = JSON.parse(rawContract);
  } catch {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "security_contract_invalid_json",
      requiredUserAction: [`Fix invalid JSON in ${contractPath}.`],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  const validated = validateSecurityPlanContract(contract);
  if (!validated.ok) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: validated.reasonCode,
      requiredUserAction: validated.errors,
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  const modeResult: SecurityModeExecutionResult =
    validated.contract.securityMode === "blackbox"
      ? await executeBlackboxSecurityMode({
          workspaceRootAbs: args.input.workspaceRootAbs,
          projectName: args.input.projectName,
          executionProfile: args.input.executionProfile,
          planName: args.planName,
          runId: `${args.input.suiteRunId ?? "security"}-${args.planName}`,
          contract: validated.contract,
          mcpInvoke: args.input.mcpInvoke,
        })
      : await executeSidecarAssistedSecurityMode({
          workspaceRootAbs: args.input.workspaceRootAbs,
          projectName: args.input.projectName,
          executionProfile: args.input.executionProfile,
          planName: args.planName,
          runId: `${args.input.suiteRunId ?? "security"}-${args.planName}`,
          contract: validated.contract,
          mcpInvoke: args.input.mcpInvoke,
        });
  return {
    status: modeResult.status,
    runStatus: modeResult.runStatus,
    ...(modeResult.runId ? { runId: modeResult.runId } : {}),
    ...(modeResult.reasonCode ? { reasonCode: modeResult.reasonCode } : {}),
    ...(modeResult.requiredUserAction ? { requiredUserAction: modeResult.requiredUserAction } : {}),
    reasonMeta: {
      securityMode: validated.contract.securityMode,
      planName: args.planName,
      ...(modeResult.reasonMeta ?? {}),
    },
  };
}

export async function executeSecurityRuntimeSuite(
  args: ExecuteSecurityRuntimeSuiteArgs,
): Promise<RuntimeSuiteRunResult> {
  const suiteRunId = buildSuiteRunId(args);
  const suite = await readSecuritySuiteManifest(args);
  if (!suite.ok) {
    return blockedSuiteResult({
      executionProfile: args.executionProfile,
      executionPolicy: "stop_on_fail",
      suiteRunId,
      reasonCode: suite.reasonCode,
      requiredUserAction: suite.requiredUserAction,
    });
  }
  const orderedPlans = [...suite.manifest.plans].sort((left, right) => left.order - right.order);
  if (orderedPlans.length === 0) {
    return blockedSuiteResult({
      executionProfile: suite.manifest.executionProfile,
      executionPolicy: suite.manifest.executionPolicy,
      suiteRunId,
      reasonCode: "security_plan_required",
      requiredUserAction: ["Add at least one security plan to the selected execution profile."],
    });
  }

  const startPlanOrder = args.startPlanOrder ?? 1;
  if (
    !Number.isInteger(startPlanOrder) ||
    startPlanOrder < 1 ||
    startPlanOrder > orderedPlans.length + 1
  ) {
    return blockedSuiteResult({
      executionProfile: suite.manifest.executionProfile,
      executionPolicy: suite.manifest.executionPolicy,
      suiteRunId,
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [`Set startPlanOrder within 1..${orderedPlans.length + 1}.`],
    });
  }
  const maxPlansPerCall = args.maxPlansPerCall;
  if (
    maxPlansPerCall !== undefined &&
    (!Number.isInteger(maxPlansPerCall) || maxPlansPerCall < 1)
  ) {
    return blockedSuiteResult({
      executionProfile: suite.manifest.executionProfile,
      executionPolicy: suite.manifest.executionPolicy,
      suiteRunId,
      reasonCode: "suite_progress_invalid",
      requiredUserAction: ["Set maxPlansPerCall as a positive integer."],
    });
  }

  const planRuns: RuntimeSuitePlanRunResult[] = (args.priorPlanRuns ?? []).map((entry) => ({
    ...entry,
  }));
  let processedPlans = 0;
  let stop = false;
  let hasBlocked = planRuns.some(
    (entry) => entry.status === "blocked" || entry.runStatus === "blocked",
  );
  let hasFail = planRuns.some((entry) => entry.runStatus === "fail");
  let firstReasonCode: string | undefined;
  let nextPlanOrder: number | undefined;
  let lastCompletedPlan: RuntimeSuitePlanRunResult | undefined;

  for (const plan of orderedPlans) {
    if (plan.order < startPlanOrder) continue;
    if (stop) {
      upsertPlanRun(planRuns, { order: plan.order, planName: plan.planName, status: "skipped" });
      continue;
    }
    if (maxPlansPerCall !== undefined && processedPlans >= maxPlansPerCall) {
      nextPlanOrder = plan.order;
      break;
    }

    const execution = await executeSecurityPlan({
      input: { ...args, suiteRunId },
      planName: plan.planName,
    });
    processedPlans += 1;
    const run: RuntimeSuitePlanRunResult = {
      order: plan.order,
      planName: plan.planName,
      status: execution.status,
      runStatus: execution.runStatus,
      ...(execution.runId ? { runId: execution.runId } : {}),
      ...(execution.status === "blocked"
        ? {
            ...(execution.reasonCode ? { blockedReasonCode: execution.reasonCode } : {}),
            blockedReasonMeta: {
              ...execution.reasonMeta,
              ...(execution.requiredUserAction
                ? { requiredUserAction: execution.requiredUserAction }
                : {}),
            },
          }
        : {}),
    };
    upsertPlanRun(planRuns, run);
    lastCompletedPlan = run;
    hasBlocked ||= execution.runStatus === "blocked";
    hasFail ||= execution.runStatus === "fail";
    firstReasonCode ??= execution.reasonCode;
    if (
      (execution.runStatus === "blocked" || execution.runStatus === "fail") &&
      effectiveOnFail({ plan, executionPolicy: suite.manifest.executionPolicy }) === "stop"
    ) {
      stop = true;
    }
  }

  if (nextPlanOrder !== undefined) {
    const completedPlanCount = planRuns.filter((plan) => plan.status !== "skipped").length;
    return {
      executionProfile: suite.manifest.executionProfile,
      executionPolicy: suite.manifest.executionPolicy,
      status: "in_progress",
      ...(firstReasonCode ? { reasonCode: firstReasonCode } : {}),
      planRuns,
      suiteRunId,
      nextPlanOrder,
      completedPlanCount,
      progressSummary: {
        progressState: "ready_for_next_plan",
        totalPlanCount: orderedPlans.length,
        completedPlanCount,
        remainingPlanCount: Math.max(orderedPlans.length - completedPlanCount, 0),
        ...(lastCompletedPlan
          ? {
              lastCompletedPlan: {
                order: lastCompletedPlan.order,
                planName: lastCompletedPlan.planName,
                status: lastCompletedPlan.status === "blocked" ? "blocked" : "executed",
                ...(lastCompletedPlan.runStatus && lastCompletedPlan.runStatus !== "in_progress"
                  ? { runStatus: lastCompletedPlan.runStatus }
                  : {}),
                ...(lastCompletedPlan.runId ? { runId: lastCompletedPlan.runId } : {}),
              },
            }
          : {}),
      },
    };
  }

  const status = resolveSecuritySuiteStatus({
    executionPolicy: suite.manifest.executionPolicy,
    hasBlocked,
    hasFail,
  });
  return {
    executionProfile: suite.manifest.executionProfile,
    executionPolicy: suite.manifest.executionPolicy,
    status,
    ...(firstReasonCode ? { reasonCode: firstReasonCode } : {}),
    planRuns,
    suiteRunId,
    completedPlanCount: planRuns.filter((plan) => plan.status !== "skipped").length,
    progressSummary: buildProgressSummary({ totalPlanCount: orderedPlans.length, planRuns }),
  };
}
