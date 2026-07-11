/**
 * Regression Suite state support: manifest/state persistence, progress
 * summaries, correlation session collection, and suite-level result writes.
 */
import path from "node:path";
import { promises as fs } from "node:fs";

import type { PlanContract } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  RuntimeSuiteManifest,
  RuntimeSuiteCorrelationResult,
  RuntimeSuiteCompletedPlanSummary,
  RuntimeSuiteProgressSummary,
  RuntimeSuiteRunResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import type { RegressionRunExecutionResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sortPlanContractWatchers(
  watchers: NonNullable<PlanContract["watchers"]>,
): NonNullable<PlanContract["watchers"]> {
  return [...watchers].sort((lhs, rhs) => {
    if (lhs.dependency.stepOrder !== rhs.dependency.stepOrder) {
      return lhs.dependency.stepOrder - rhs.dependency.stepOrder;
    }
    return lhs.id.localeCompare(rhs.id);
  });
}

async function readJsonFile(absPath: string): Promise<Record<string, unknown> | null> {
  try {
    const text = await fs.readFile(absPath, "utf8");
    const parsed = JSON.parse(text) as unknown;
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export async function readPersistedRegressionPlanState(args: {
  workspaceRootAbs: string;
  projectName?: string;
  planName: string;
  runId: string;
}): Promise<{
  resolvedContext: Record<string, unknown>;
  executionResult: RegressionRunExecutionResult;
  evidence: Record<string, unknown>;
} | null> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const runDirAbs = path.join(plansRootAbs, args.planName, "runs", args.runId);
  const [resolvedContext, executionResult, evidence] = await Promise.all([
    readJsonFile(path.join(runDirAbs, "context.resolved.json")),
    readJsonFile(path.join(runDirAbs, "execution.result.json")),
    readJsonFile(path.join(runDirAbs, "evidence.json")),
  ]);
  if (!resolvedContext || !executionResult || !evidence) {
    return null;
  }
  return {
    resolvedContext,
    executionResult: executionResult as unknown as RegressionRunExecutionResult,
    evidence,
  };
}

function buildSuiteStatusDirAbs(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
}): string {
  return path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "suite-runs",
    args.suiteRunId,
  );
}

export function buildSuiteStatusArtifactRelPath(args: {
  projectName: string;
  suiteRunId: string;
}): string {
  return `.mcpjvm/${args.projectName}/suite-runs/${args.suiteRunId}/execution_orchestration.result.json`;
}

export async function writeExecutionOrchestrationSuiteResult(args: {
  workspaceRootAbs: string;
  projectName: string;
  suite: RuntimeSuiteRunResult;
}): Promise<string> {
  const suiteRunId = args.suite.suiteRunId?.trim();
  if (!suiteRunId) throw new Error("suite_run_id_required");
  const dirAbs = buildSuiteStatusDirAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    suiteRunId,
  });
  await fs.mkdir(dirAbs, { recursive: true });
  const fileAbs = path.join(dirAbs, "execution_orchestration.result.json");
  const payload: Record<string, unknown> = {
    resultType: "execution_orchestration",
    action: "execute",
    projectName: args.projectName,
    executionProfile: args.suite.executionProfile,
    status: args.suite.status,
    suiteRunId,
    executionPolicy: args.suite.executionPolicy,
    planRuns: args.suite.planRuns,
    completedPlanCount: args.suite.completedPlanCount ?? args.suite.planRuns.length,
  };
  if (typeof args.suite.reasonCode === "string") payload.reasonCode = args.suite.reasonCode;
  if (isRecord(args.suite.reasonMeta)) payload.reasonMeta = args.suite.reasonMeta;
  if (typeof args.suite.nextPlanOrder === "number")
    payload.nextPlanOrder = args.suite.nextPlanOrder;
  if (Array.isArray(args.suite.correlations) && args.suite.correlations.length > 0) {
    payload.correlations = args.suite.correlations;
  }
  if (isRecord(args.suite.suiteContext) && Object.keys(args.suite.suiteContext).length > 0) {
    payload.suiteContext = args.suite.suiteContext;
  }
  if (isRecord(args.suite.progressSummary)) {
    payload.progressSummary = args.suite.progressSummary;
  }
  await fs.writeFile(fileAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  return fileAbs;
}

function asPersistedPlanRunResult(
  value: unknown,
): RuntimeSuiteRunResult["planRuns"][number] | null {
  if (!isRecord(value)) return null;
  if (typeof value.order !== "number" || !Number.isInteger(value.order) || value.order <= 0)
    return null;
  if (typeof value.planName !== "string" || value.planName.trim().length === 0) return null;
  if (value.status !== "executed" && value.status !== "blocked" && value.status !== "skipped")
    return null;
  if (
    typeof value.runStatus !== "undefined" &&
    value.runStatus !== "pass" &&
    value.runStatus !== "fail" &&
    value.runStatus !== "blocked" &&
    value.runStatus !== "in_progress"
  ) {
    return null;
  }
  return {
    order: value.order,
    planName: value.planName.trim(),
    status: value.status,
    ...(typeof value.runStatus === "string" ? { runStatus: value.runStatus } : {}),
    ...(typeof value.blockedReasonCode === "string"
      ? { blockedReasonCode: value.blockedReasonCode }
      : {}),
    ...(isRecord(value.blockedReasonMeta) ? { blockedReasonMeta: value.blockedReasonMeta } : {}),
    ...(typeof value.runId === "string" ? { runId: value.runId } : {}),
  };
}

export function upsertPlanRun(
  planRuns: RuntimeSuiteRunResult["planRuns"],
  nextPlanRun: RuntimeSuiteRunResult["planRuns"][number],
): void {
  const existingIndex = planRuns.findIndex((entry) => entry.order === nextPlanRun.order);
  if (existingIndex >= 0) {
    planRuns[existingIndex] = nextPlanRun;
    return;
  }
  planRuns.push(nextPlanRun);
}

export function countCompletedPlanRuns(planRuns: RuntimeSuiteRunResult["planRuns"]): number {
  return planRuns.filter((entry) => entry.status !== "skipped" && entry.runStatus !== "in_progress")
    .length;
}

function buildCompletedPlanSummary(
  planRuns: RuntimeSuiteRunResult["planRuns"],
): RuntimeSuiteCompletedPlanSummary | undefined {
  const completed = [...planRuns]
    .filter(
      (entry) =>
        entry.status !== "skipped" &&
        entry.runStatus !== "in_progress" &&
        (entry.status === "blocked" || typeof entry.runStatus === "string"),
    )
    .sort((lhs, rhs) => rhs.order - lhs.order);
  const latest = completed[0];
  if (!latest) {
    return undefined;
  }
  return {
    order: latest.order,
    planName: latest.planName,
    status: latest.status === "blocked" ? "blocked" : "executed",
    ...(latest.runStatus && latest.runStatus !== "in_progress"
      ? { runStatus: latest.runStatus }
      : {}),
    ...(typeof latest.runId === "string" ? { runId: latest.runId } : {}),
  };
}

function readContinuationTargetSummary(args: {
  contract: PlanContract;
  executionResult: RegressionRunExecutionResult;
}): NonNullable<RuntimeSuiteProgressSummary["activePlan"]>["waitingOn"] | undefined {
  const continuation = args.executionResult.continuation;
  if (!continuation) {
    return undefined;
  }
  if (continuation.phase === "watchers") {
    const watchers = sortPlanContractWatchers(args.contract.watchers ?? []);
    const watcher = watchers[continuation.watcherIndex];
    return {
      targetType: "watcher",
      ...(typeof watcher?.id === "string" ? { targetId: watcher.id } : {}),
      ...(typeof watcher?.provider?.type === "string"
        ? { providerType: watcher.provider.type }
        : {}),
      currentIndex: continuation.watcherIndex + 1,
      totalCount: watchers.length,
    };
  }

  const verification = args.contract.externalVerification?.[continuation.verificationIndex];
  return {
    targetType: "external_verification",
    ...(typeof verification?.id === "string" ? { targetId: verification.id } : {}),
    ...(typeof verification?.provider?.type === "string"
      ? { providerType: verification.provider.type }
      : {}),
    currentIndex: continuation.verificationIndex + 1,
    totalCount: args.contract.externalVerification?.length ?? 0,
  };
}

async function readPlanContract(args: {
  workspaceRootAbs: string;
  projectName?: string;
  planName: string;
}): Promise<PlanContract | null> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const contract = await readJsonFile(path.join(plansRootAbs, args.planName, "contract.json"));
  return contract as unknown as PlanContract | null;
}

export async function buildRuntimeSuiteProgressSummary(args: {
  workspaceRootAbs: string;
  projectName?: string;
  manifest: RuntimeSuiteManifest;
  status: RuntimeSuiteRunResult["status"];
  planRuns: RuntimeSuiteRunResult["planRuns"];
  activeExecutionResult?: RegressionRunExecutionResult;
}): Promise<RuntimeSuiteProgressSummary> {
  const totalPlanCount = args.manifest.plans.length;
  const completedPlanCount = countCompletedPlanRuns(args.planRuns);
  const activePlanRun = args.planRuns.find((entry) => entry.runStatus === "in_progress");
  const progressState =
    args.status === "in_progress"
      ? activePlanRun
        ? "waiting_in_active_plan"
        : "ready_for_next_plan"
      : "terminal";
  const remainingPlanCount = Math.max(
    0,
    totalPlanCount - completedPlanCount - (activePlanRun ? 1 : 0),
  );
  const lastCompletedPlan = buildCompletedPlanSummary(args.planRuns);

  const summary: RuntimeSuiteProgressSummary = {
    progressState,
    totalPlanCount,
    completedPlanCount,
    remainingPlanCount,
    ...(lastCompletedPlan ? { lastCompletedPlan } : {}),
  };

  if (!activePlanRun || !args.activeExecutionResult) {
    return summary;
  }

  const contract = await readPlanContract(
    typeof args.projectName === "string"
      ? {
          workspaceRootAbs: args.workspaceRootAbs,
          projectName: args.projectName,
          planName: activePlanRun.planName,
        }
      : {
          workspaceRootAbs: args.workspaceRootAbs,
          planName: activePlanRun.planName,
        },
  );
  const continuation = args.activeExecutionResult.continuation;
  const phase =
    continuation?.phase ??
    (args.activeExecutionResult.externalVerificationStatus === "in_progress"
      ? "external_verification"
      : args.activeExecutionResult.watcherStatus === "in_progress"
        ? "watchers"
        : "trigger");

  const waitingOn =
    contract && continuation
      ? readContinuationTargetSummary({ contract, executionResult: args.activeExecutionResult })
      : undefined;

  summary.activePlan = {
    order: activePlanRun.order,
    planName: activePlanRun.planName,
    ...(typeof activePlanRun.runId === "string" ? { runId: activePlanRun.runId } : {}),
    phase,
    ...(typeof continuation?.phaseStartedAt === "string"
      ? { phaseStartedAt: continuation.phaseStartedAt }
      : {}),
    ...(typeof args.activeExecutionResult.endedAt !== "undefined"
      ? { lastUpdatedAt: args.activeExecutionResult.endedAt }
      : {}),
    ...(typeof args.activeExecutionResult.triggerStatus === "string"
      ? { triggerStatus: args.activeExecutionResult.triggerStatus }
      : {}),
    ...(typeof args.activeExecutionResult.watcherStatus === "string"
      ? { watcherStatus: args.activeExecutionResult.watcherStatus }
      : {}),
    ...(typeof args.activeExecutionResult.externalVerificationStatus === "string"
      ? { externalVerificationStatus: args.activeExecutionResult.externalVerificationStatus }
      : {}),
    ...(waitingOn ? { waitingOn } : {}),
  };

  return summary;
}

export function resolveBlockedPlanDetail(executionResult: Record<string, unknown>): {
  blockedReasonCode?: string;
  blockedReasonMeta?: Record<string, unknown>;
} {
  const steps = Array.isArray(executionResult.steps) ? executionResult.steps : [];
  const blockedStep =
    steps.find((entry) => isRecord(entry) && entry.status === "blocked_runtime") ??
    steps.find((entry) => isRecord(entry) && entry.status === "blocked_dependency");
  if (isRecord(blockedStep)) {
    return {
      ...(typeof blockedStep.reasonCode === "string"
        ? { blockedReasonCode: blockedStep.reasonCode }
        : {}),
      ...(isRecord(blockedStep.reasonMeta) ? { blockedReasonMeta: blockedStep.reasonMeta } : {}),
    };
  }

  const watchers = Array.isArray(executionResult.watchers) ? executionResult.watchers : [];
  const blockedWatcher =
    watchers.find((entry) => isRecord(entry) && entry.status === "blocked_runtime") ??
    watchers.find((entry) => isRecord(entry) && entry.status === "blocked_dependency");
  if (!isRecord(blockedWatcher)) return {};
  return {
    ...(typeof blockedWatcher.reasonCode === "string"
      ? { blockedReasonCode: blockedWatcher.reasonCode }
      : {}),
    ...(isRecord(blockedWatcher.reasonMeta)
      ? { blockedReasonMeta: blockedWatcher.reasonMeta }
      : {}),
  };
}

function asPersistedCorrelationResult(value: unknown): RuntimeSuiteCorrelationResult | null {
  if (!isRecord(value)) return null;
  if (
    typeof value.correlationSessionId !== "string" ||
    value.correlationSessionId.trim().length === 0
  )
    return null;
  if (value.status !== "ok" && value.status !== "fail_closed") return null;
  if (typeof value.reasonCode !== "string" || value.reasonCode.trim().length === 0) return null;
  if (value.keyType !== "traceId" && value.keyType !== "requestId" && value.keyType !== "messageId")
    return null;
  if (
    !Array.isArray(value.contributingPlans) ||
    value.contributingPlans.some((entry) => typeof entry !== "string")
  ) {
    return null;
  }
  return {
    correlationSessionId: value.correlationSessionId.trim(),
    status: value.status,
    reasonCode: value.reasonCode.trim(),
    keyType: value.keyType,
    ...(typeof value.keyValue === "string" ? { keyValue: value.keyValue } : {}),
    contributingPlans: value.contributingPlans.map((entry) => String(entry)),
  };
}

function asPersistedCompletedPlanSummary(value: unknown): RuntimeSuiteCompletedPlanSummary | null {
  if (!isRecord(value)) return null;
  if (typeof value.order !== "number" || !Number.isInteger(value.order) || value.order <= 0)
    return null;
  if (typeof value.planName !== "string" || value.planName.trim().length === 0) return null;
  if (value.status !== "executed" && value.status !== "blocked") return null;
  if (
    typeof value.runStatus !== "undefined" &&
    value.runStatus !== "pass" &&
    value.runStatus !== "fail" &&
    value.runStatus !== "blocked"
  ) {
    return null;
  }
  return {
    order: value.order,
    planName: value.planName.trim(),
    status: value.status,
    ...(typeof value.runStatus === "string" ? { runStatus: value.runStatus } : {}),
    ...(typeof value.runId === "string" ? { runId: value.runId } : {}),
  };
}

function asPersistedProgressTargetSummary(
  value: unknown,
): NonNullable<RuntimeSuiteProgressSummary["activePlan"]>["waitingOn"] | null {
  if (!isRecord(value)) return null;
  if (value.targetType !== "watcher" && value.targetType !== "external_verification") return null;
  if (
    typeof value.currentIndex !== "number" ||
    !Number.isInteger(value.currentIndex) ||
    value.currentIndex <= 0 ||
    typeof value.totalCount !== "number" ||
    !Number.isInteger(value.totalCount) ||
    value.totalCount < 0
  ) {
    return null;
  }
  return {
    targetType: value.targetType,
    ...(typeof value.targetId === "string" ? { targetId: value.targetId } : {}),
    ...(typeof value.providerType === "string" ? { providerType: value.providerType } : {}),
    currentIndex: value.currentIndex,
    totalCount: value.totalCount,
  };
}

function asPersistedProgressSummary(value: unknown): RuntimeSuiteProgressSummary | null {
  if (!isRecord(value)) return null;
  if (
    value.progressState !== "ready_for_next_plan" &&
    value.progressState !== "waiting_in_active_plan" &&
    value.progressState !== "terminal"
  ) {
    return null;
  }
  if (
    typeof value.totalPlanCount !== "number" ||
    !Number.isInteger(value.totalPlanCount) ||
    value.totalPlanCount < 0 ||
    typeof value.completedPlanCount !== "number" ||
    !Number.isInteger(value.completedPlanCount) ||
    value.completedPlanCount < 0 ||
    typeof value.remainingPlanCount !== "number" ||
    !Number.isInteger(value.remainingPlanCount) ||
    value.remainingPlanCount < 0
  ) {
    return null;
  }

  let activePlan: RuntimeSuiteProgressSummary["activePlan"] | undefined;
  if (typeof value.activePlan !== "undefined") {
    if (!isRecord(value.activePlan)) return null;
    const waitingOn =
      typeof value.activePlan.waitingOn === "undefined"
        ? undefined
        : asPersistedProgressTargetSummary(value.activePlan.waitingOn);
    if (
      typeof value.activePlan.order !== "number" ||
      !Number.isInteger(value.activePlan.order) ||
      value.activePlan.order <= 0
    ) {
      return null;
    }
    if (
      typeof value.activePlan.planName !== "string" ||
      value.activePlan.planName.trim().length === 0
    ) {
      return null;
    }
    if (
      value.activePlan.phase !== "trigger" &&
      value.activePlan.phase !== "watchers" &&
      value.activePlan.phase !== "external_verification"
    ) {
      return null;
    }
    if (typeof value.activePlan.waitingOn !== "undefined" && !waitingOn) {
      return null;
    }
    const triggerStatus =
      value.activePlan.triggerStatus === "pass" ||
      value.activePlan.triggerStatus === "fail" ||
      value.activePlan.triggerStatus === "blocked" ||
      value.activePlan.triggerStatus === "in_progress"
        ? value.activePlan.triggerStatus
        : undefined;
    const watcherStatus =
      value.activePlan.watcherStatus === "not_configured" ||
      value.activePlan.watcherStatus === "pass" ||
      value.activePlan.watcherStatus === "fail" ||
      value.activePlan.watcherStatus === "blocked" ||
      value.activePlan.watcherStatus === "in_progress"
        ? value.activePlan.watcherStatus
        : undefined;
    const externalVerificationStatus =
      value.activePlan.externalVerificationStatus === "not_configured" ||
      value.activePlan.externalVerificationStatus === "pass" ||
      value.activePlan.externalVerificationStatus === "fail" ||
      value.activePlan.externalVerificationStatus === "blocked" ||
      value.activePlan.externalVerificationStatus === "in_progress" ||
      value.activePlan.externalVerificationStatus === "skipped_dependency"
        ? value.activePlan.externalVerificationStatus
        : undefined;
    if (typeof value.activePlan.triggerStatus !== "undefined" && !triggerStatus) {
      return null;
    }
    if (typeof value.activePlan.watcherStatus !== "undefined" && !watcherStatus) {
      return null;
    }
    if (
      typeof value.activePlan.externalVerificationStatus !== "undefined" &&
      !externalVerificationStatus
    ) {
      return null;
    }
    activePlan = {
      order: value.activePlan.order,
      planName: value.activePlan.planName.trim(),
      phase: value.activePlan.phase,
      ...(typeof value.activePlan.runId === "string" ? { runId: value.activePlan.runId } : {}),
      ...(typeof value.activePlan.phaseStartedAt === "string"
        ? { phaseStartedAt: value.activePlan.phaseStartedAt }
        : {}),
      ...(typeof value.activePlan.lastUpdatedAt === "string" ||
      value.activePlan.lastUpdatedAt === null
        ? { lastUpdatedAt: value.activePlan.lastUpdatedAt as string | null }
        : {}),
      ...(triggerStatus ? { triggerStatus } : {}),
      ...(watcherStatus ? { watcherStatus } : {}),
      ...(externalVerificationStatus ? { externalVerificationStatus } : {}),
      ...(waitingOn ? { waitingOn } : {}),
    };
  }

  const lastCompletedPlan =
    typeof value.lastCompletedPlan === "undefined"
      ? undefined
      : asPersistedCompletedPlanSummary(value.lastCompletedPlan);
  if (typeof value.lastCompletedPlan !== "undefined" && !lastCompletedPlan) {
    return null;
  }

  return {
    progressState: value.progressState,
    totalPlanCount: value.totalPlanCount,
    completedPlanCount: value.completedPlanCount,
    remainingPlanCount: value.remainingPlanCount,
    ...(activePlan ? { activePlan } : {}),
    ...(lastCompletedPlan ? { lastCompletedPlan } : {}),
  };
}

export async function readExecutionOrchestrationSuiteResult(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
}): Promise<RuntimeSuiteRunResult | null> {
  const suiteRunId = args.suiteRunId.trim();
  if (!suiteRunId) return null;
  const fileAbs = path.join(
    buildSuiteStatusDirAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      suiteRunId,
    }),
    "execution_orchestration.result.json",
  );
  const parsed = await readJsonFile(fileAbs);
  if (!parsed) return null;
  if (
    parsed.resultType !== "execution_orchestration" ||
    parsed.action !== "execute" ||
    parsed.projectName !== args.projectName ||
    typeof parsed.executionProfile !== "string" ||
    (parsed.status !== "pass" &&
      parsed.status !== "fail" &&
      parsed.status !== "blocked" &&
      parsed.status !== "partial_fail" &&
      parsed.status !== "in_progress")
  ) {
    return null;
  }
  if (parsed.suiteRunId !== suiteRunId) return null;
  if (parsed.executionPolicy !== "stop_on_fail" && parsed.executionPolicy !== "continue_on_fail")
    return null;
  if (typeof parsed.reasonCode !== "undefined" && typeof parsed.reasonCode !== "string")
    return null;
  if (typeof parsed.reasonMeta !== "undefined" && !isRecord(parsed.reasonMeta)) return null;
  if (!Array.isArray(parsed.planRuns)) return null;
  const planRuns = parsed.planRuns
    .map((entry) => asPersistedPlanRunResult(entry))
    .filter(
      (entry): entry is NonNullable<ReturnType<typeof asPersistedPlanRunResult>> => entry !== null,
    );
  if (planRuns.length !== parsed.planRuns.length) return null;
  const correlations = Array.isArray(parsed.correlations)
    ? parsed.correlations
        .map((entry) => asPersistedCorrelationResult(entry))
        .filter((entry): entry is RuntimeSuiteCorrelationResult => entry !== null)
    : undefined;
  if (
    Array.isArray(parsed.correlations) &&
    correlations &&
    correlations.length !== parsed.correlations.length
  )
    return null;
  const suiteContext = isRecord(parsed.suiteContext) ? parsed.suiteContext : undefined;
  const progressSummary =
    typeof parsed.progressSummary === "undefined"
      ? undefined
      : asPersistedProgressSummary(parsed.progressSummary);
  if (typeof parsed.progressSummary !== "undefined" && !progressSummary) return null;
  return {
    executionProfile: parsed.executionProfile,
    executionPolicy: parsed.executionPolicy,
    status: parsed.status,
    ...(typeof parsed.reasonCode === "string" ? { reasonCode: parsed.reasonCode } : {}),
    ...(isRecord(parsed.reasonMeta) ? { reasonMeta: parsed.reasonMeta } : {}),
    suiteRunId,
    planRuns,
    ...(typeof parsed.nextPlanOrder === "number" ? { nextPlanOrder: parsed.nextPlanOrder } : {}),
    ...(typeof parsed.completedPlanCount === "number"
      ? { completedPlanCount: parsed.completedPlanCount }
      : {}),
    ...(correlations ? { correlations } : {}),
    ...(suiteContext ? { suiteContext } : {}),
    ...(progressSummary ? { progressSummary } : {}),
  };
}

export function isSuiteLevelPreflightBlocker(reasonCode: string | undefined): boolean {
  return (
    reasonCode === "env_key_missing" ||
    reasonCode === "script_execution_failed" ||
    reasonCode === "external_healthcheck_failed" ||
    reasonCode === "runtime_context_unknown" ||
    reasonCode === "project_artifact_missing" ||
    reasonCode === "project_artifact_invalid" ||
    reasonCode === "project_reference_invalid" ||
    reasonCode === "workspace_root_invalid" ||
    reasonCode === "external_system_invalid"
  );
}
