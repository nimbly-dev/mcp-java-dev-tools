import path from "node:path";
import { promises as fs } from "node:fs";

import type {
  RuntimeSuiteManifest,
  RuntimeSuiteCorrelationResult,
  RuntimeSuitePlanEntry,
  RuntimeSuiteRunResult,
  RuntimeSuiteScriptPhase,
  RuntimeSuiteScriptRef,
} from "@tools-regression-execution-plan-spec/models/regression_runtime_suite.model";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import type {
  CorrelationArtifact,
  RegressionRunExecutionResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import {
  executeRegressionPlanWorkflow,
  type ExecuteRegressionPlanWorkflowArgs,
} from "@tools-regression-execution-plan-spec/regression_plan_executor.util";
import { correlateEvents } from "@tools-regression-execution-plan-spec/regression_correlation.util";
import { buildTimestampRunId } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRuntimeSuiteScriptPhase(value: string): value is RuntimeSuiteScriptPhase {
  return value === "preRuntime" || value === "postRuntime" || value === "postHealthcheck" || value === "prePlan";
}

type CanonicalCorrelationEvent = {
  eventId: string;
  probeId: string;
  timestampEpochMs: number;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  lineKey?: string;
  eventType?: string;
};

type RuntimeSuiteCorrelationSession = {
  correlationSessionId: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  maxWindowMs: number;
  expectedFlow?: string[];
  contributingPlans: Set<string>;
  events: CanonicalCorrelationEvent[];
};

const SUITE_CORRELATION_LAST_KEY_VALUE = "suite.correlation.last.keyValue";
const SUITE_CORRELATION_LAST_KEY_TYPE = "suite.correlation.last.keyType";
const SUITE_CORRELATION_LAST_SESSION_ID = "suite.correlation.last.correlationSessionId";
const SUITE_CORRELATION_LAST_SOURCE_PLAN = "suite.correlation.last.sourcePlanName";

function isReplayScriptPath(value: string): boolean {
  const normalized = value.replaceAll("\\", "/").toLowerCase();
  if (normalized.endsWith(".ps1") || normalized.endsWith(".sh")) {
    return true;
  }
  return false;
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

async function readPersistedRegressionPlanState(args: {
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
  if (typeof args.suite.nextPlanOrder === "number") payload.nextPlanOrder = args.suite.nextPlanOrder;
  if (Array.isArray(args.suite.correlations) && args.suite.correlations.length > 0) {
    payload.correlations = args.suite.correlations;
  }
  if (isRecord(args.suite.suiteContext) && Object.keys(args.suite.suiteContext).length > 0) {
    payload.suiteContext = args.suite.suiteContext;
  }
  await fs.writeFile(fileAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  return fileAbs;
}

function asPersistedPlanRunResult(value: unknown): RuntimeSuiteRunResult["planRuns"][number] | null {
  if (!isRecord(value)) return null;
  if (typeof value.order !== "number" || !Number.isInteger(value.order) || value.order <= 0) return null;
  if (typeof value.planName !== "string" || value.planName.trim().length === 0) return null;
  if (value.status !== "executed" && value.status !== "blocked" && value.status !== "skipped") return null;
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
    ...(typeof value.blockedReasonCode === "string" ? { blockedReasonCode: value.blockedReasonCode } : {}),
    ...(isRecord(value.blockedReasonMeta) ? { blockedReasonMeta: value.blockedReasonMeta } : {}),
    ...(typeof value.runId === "string" ? { runId: value.runId } : {}),
  };
}

function upsertPlanRun(
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

function countCompletedPlanRuns(planRuns: RuntimeSuiteRunResult["planRuns"]): number {
  return planRuns.filter((entry) => entry.status !== "skipped" && entry.runStatus !== "in_progress").length;
}

function resolveBlockedPlanDetail(executionResult: Record<string, unknown>): {
  blockedReasonCode?: string;
  blockedReasonMeta?: Record<string, unknown>;
} {
  const steps = Array.isArray(executionResult.steps) ? executionResult.steps : [];
  const blockedStep = steps.find((entry) => isRecord(entry) && entry.status === "blocked_runtime") ??
    steps.find((entry) => isRecord(entry) && entry.status === "blocked_dependency");
  if (isRecord(blockedStep)) {
    return {
      ...(typeof blockedStep.reasonCode === "string" ? { blockedReasonCode: blockedStep.reasonCode } : {}),
      ...(isRecord(blockedStep.reasonMeta) ? { blockedReasonMeta: blockedStep.reasonMeta } : {}),
    };
  }

  const watchers = Array.isArray(executionResult.watchers) ? executionResult.watchers : [];
  const blockedWatcher = watchers.find((entry) => isRecord(entry) && entry.status === "blocked_runtime") ??
    watchers.find((entry) => isRecord(entry) && entry.status === "blocked_dependency");
  if (!isRecord(blockedWatcher)) return {};
  return {
    ...(typeof blockedWatcher.reasonCode === "string" ? { blockedReasonCode: blockedWatcher.reasonCode } : {}),
    ...(isRecord(blockedWatcher.reasonMeta) ? { blockedReasonMeta: blockedWatcher.reasonMeta } : {}),
  };
}

function asPersistedCorrelationResult(value: unknown): RuntimeSuiteCorrelationResult | null {
  if (!isRecord(value)) return null;
  if (typeof value.correlationSessionId !== "string" || value.correlationSessionId.trim().length === 0) return null;
  if (value.status !== "ok" && value.status !== "fail_closed") return null;
  if (typeof value.reasonCode !== "string" || value.reasonCode.trim().length === 0) return null;
  if (value.keyType !== "traceId" && value.keyType !== "requestId" && value.keyType !== "messageId") return null;
  if (!Array.isArray(value.contributingPlans) || value.contributingPlans.some((entry) => typeof entry !== "string")) {
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
  if (parsed.executionPolicy !== "stop_on_fail" && parsed.executionPolicy !== "continue_on_fail") return null;
  if (!Array.isArray(parsed.planRuns)) return null;
  const planRuns = parsed.planRuns
    .map((entry) => asPersistedPlanRunResult(entry))
    .filter((entry): entry is NonNullable<ReturnType<typeof asPersistedPlanRunResult>> => entry !== null);
  if (planRuns.length !== parsed.planRuns.length) return null;
  const correlations = Array.isArray(parsed.correlations)
    ? parsed.correlations
        .map((entry) => asPersistedCorrelationResult(entry))
        .filter((entry): entry is RuntimeSuiteCorrelationResult => entry !== null)
    : undefined;
  if (Array.isArray(parsed.correlations) && correlations && correlations.length !== parsed.correlations.length) return null;
  const suiteContext = isRecord(parsed.suiteContext) ? parsed.suiteContext : undefined;
  return {
    executionProfile: parsed.executionProfile,
    executionPolicy: parsed.executionPolicy,
    status: parsed.status,
    suiteRunId,
    planRuns,
    ...(typeof parsed.nextPlanOrder === "number" ? { nextPlanOrder: parsed.nextPlanOrder } : {}),
    ...(typeof parsed.completedPlanCount === "number" ? { completedPlanCount: parsed.completedPlanCount } : {}),
    ...(correlations ? { correlations } : {}),
    ...(suiteContext ? { suiteContext } : {}),
  };
}

function asCorrelationKeyType(value: unknown): "traceId" | "requestId" | "messageId" {
  return value === "requestId" ? "requestId" : value === "messageId" ? "messageId" : "traceId";
}

function asCanonicalCorrelationEvent(value: unknown): CanonicalCorrelationEvent | null {
  if (!isRecord(value)) return null;
  if (
    typeof value.eventId !== "string" ||
    typeof value.probeId !== "string" ||
    typeof value.timestampEpochMs !== "number"
  ) {
    return null;
  }
  return {
    eventId: value.eventId,
    probeId: value.probeId,
    timestampEpochMs: value.timestampEpochMs,
    keyType: asCorrelationKeyType(value.keyType),
    ...(typeof value.keyValue === "string" ? { keyValue: value.keyValue } : {}),
    ...(typeof value.lineKey === "string" ? { lineKey: value.lineKey } : {}),
    ...(typeof value.eventType === "string" ? { eventType: value.eventType } : {}),
  };
}

function correlationEventsKeyValues(events: CanonicalCorrelationEvent[]): string[] {
  return Array.from(new Set(events.map((event) => event.keyValue).filter((value): value is string => typeof value === "string" && value.trim().length > 0))).sort();
}

function isSuiteContextTokenSafe(value: string): boolean {
  return /^[A-Za-z0-9_.-]+$/.test(value);
}

function resolveSuiteCorrelationSessionKeyValue(session: RuntimeSuiteCorrelationSession): string | undefined {
  const distinctKeyValues = new Set(correlationEventsKeyValues(session.events));
  if (typeof session.keyValue === "string" && session.keyValue.trim().length > 0) {
    distinctKeyValues.add(session.keyValue.trim());
  }
  return distinctKeyValues.size === 1 ? Array.from(distinctKeyValues)[0] : undefined;
}

function applySuiteCorrelationContext(args: {
  suiteContext: Record<string, unknown>;
  session: RuntimeSuiteCorrelationSession;
  sourcePlanName: string;
}): void {
  const keyValue = resolveSuiteCorrelationSessionKeyValue(args.session);
  if (!keyValue) {
    return;
  }

  args.suiteContext[SUITE_CORRELATION_LAST_KEY_VALUE] = keyValue;
  args.suiteContext[SUITE_CORRELATION_LAST_KEY_TYPE] = args.session.keyType;
  args.suiteContext[SUITE_CORRELATION_LAST_SESSION_ID] = args.session.correlationSessionId;
  args.suiteContext[SUITE_CORRELATION_LAST_SOURCE_PLAN] = args.sourcePlanName;

  if (!isSuiteContextTokenSafe(args.session.correlationSessionId)) {
    return;
  }

  const sessionPrefix = `suite.correlation.${args.session.correlationSessionId}`;
  args.suiteContext[`${sessionPrefix}.keyValue`] = keyValue;
  args.suiteContext[`${sessionPrefix}.keyType`] = args.session.keyType;
  args.suiteContext[`${sessionPrefix}.correlationSessionId`] = args.session.correlationSessionId;
  args.suiteContext[`${sessionPrefix}.sourcePlanName`] = args.sourcePlanName;
}

async function collectSuiteCorrelationSession(args: {
  runDirAbs: string;
  planName: string;
  sessions: Map<string, RuntimeSuiteCorrelationSession>;
  suiteContext?: Record<string, unknown>;
}): Promise<void> {
  const evidence = await readJsonFile(path.join(args.runDirAbs, "evidence.json"));
  if (!evidence) return;
  const policy = isRecord(evidence.correlationPolicy) ? evidence.correlationPolicy : null;
  const events = Array.isArray(evidence.correlationEvents) ? evidence.correlationEvents : [];
  const sessionId =
    policy && typeof policy.correlationSessionId === "string" && policy.correlationSessionId.trim().length > 0
      ? policy.correlationSessionId.trim()
      : undefined;
  if (!policy || !sessionId || events.length === 0) return;

  const normalizedEvents = events.map((event) => asCanonicalCorrelationEvent(event)).filter((event): event is CanonicalCorrelationEvent => event !== null);
  if (normalizedEvents.length === 0) return;

  const keyType = asCorrelationKeyType(policy.keyType);
  const expectedFlow = Array.isArray(policy.expectedFlow) ? policy.expectedFlow.map((value) => String(value)) : undefined;
  const maxWindowMs = typeof policy.maxWindowMs === "number" && Number.isFinite(policy.maxWindowMs) ? policy.maxWindowMs : 0;
  const session = args.sessions.get(sessionId) ?? {
    correlationSessionId: sessionId,
    keyType,
    ...(typeof policy.keyValue === "string" && policy.keyValue.trim().length > 0 ? { keyValue: policy.keyValue } : {}),
    maxWindowMs,
    ...(expectedFlow ? { expectedFlow } : {}),
    contributingPlans: new Set<string>(),
    events: [],
  };
  session.contributingPlans.add(args.planName);
  session.events.push(...normalizedEvents);

  if (typeof policy.keyValue === "string" && policy.keyValue.trim().length > 0) {
    session.keyValue = policy.keyValue;
  }
  if (typeof session.keyValue !== "string" || session.keyValue.trim().length === 0) {
    const distinctEventKeyValues = correlationEventsKeyValues(session.events);
    if (distinctEventKeyValues.length === 1) {
      const [resolvedKeyValue] = distinctEventKeyValues;
      if (typeof resolvedKeyValue === "string" && resolvedKeyValue.trim().length > 0) {
        session.keyValue = resolvedKeyValue;
      }
    }
  }
  if (!(session.maxWindowMs > 0) && maxWindowMs > 0) {
    session.maxWindowMs = maxWindowMs;
  }
  if (!session.expectedFlow && expectedFlow) {
    session.expectedFlow = expectedFlow;
  }
  args.sessions.set(sessionId, session);
  if (args.suiteContext) {
    applySuiteCorrelationContext({
      suiteContext: args.suiteContext,
      session,
      sourcePlanName: args.planName,
    });
  }
}

async function writeSuiteCorrelationResults(args: {
  sessions: Map<string, RuntimeSuiteCorrelationSession>;
  now: Date;
}): Promise<RuntimeSuiteCorrelationResult[]> {
  const results: RuntimeSuiteCorrelationResult[] = [];
  const sessionEntries = Array.from(args.sessions.values()).filter((entry) => entry.contributingPlans.size > 1);
  for (const session of sessionEntries) {
    const distinctKeyValues = correlationEventsKeyValues(session.events);
    const keyValue = typeof session.keyValue === "string" && session.keyValue.trim().length > 0
      ? session.keyValue
      : (distinctKeyValues.length === 1 ? distinctKeyValues[0] : undefined);

    const matched =
      typeof keyValue === "string" && keyValue.trim().length > 0
        ? correlateEvents(session.events, {
            keyType: session.keyType,
            keyValue,
            maxWindowMs: session.maxWindowMs,
            ...(session.expectedFlow ? { expectedFlow: session.expectedFlow } : {}),
          })
        : { status: "fail_closed" as const, reasonCode: "missing_correlation_key" as const, timeline: [] };

    const timeline = matched.timeline.map((event) => ({
      eventId: event.eventId,
      probeId: event.probeId,
      timestampEpochMs: event.timestampEpochMs,
      ...(typeof event.lineKey === "string" ? { lineKey: event.lineKey } : {}),
    }));
    const startEvent = timeline[0];
    const endEvent = timeline.length > 0 ? timeline[timeline.length - 1] : undefined;
    const correlation: CorrelationArtifact = {
      status: matched.status === "ok" ? "ok" : "fail_closed",
      reasonCode: matched.reasonCode === "ambiguous_correlation" ? "ambiguous_cross_plan_correlation" : matched.reasonCode,
      correlationSessionId: session.correlationSessionId,
      keyType: session.keyType,
      ...(keyValue ? { keyValue } : {}),
      window: {
        ...(typeof startEvent?.timestampEpochMs === "number" ? { startEpochMs: startEvent.timestampEpochMs } : {}),
        ...(typeof endEvent?.timestampEpochMs === "number" ? { endEpochMs: endEvent.timestampEpochMs } : {}),
        maxWindowMs: session.maxWindowMs,
      },
      ...(session.expectedFlow ? { expectedFlow: session.expectedFlow } : {}),
      timeline,
      generatedAtEpochMs: args.now.getTime(),
    };

    results.push({
      correlationSessionId: session.correlationSessionId,
      status: correlation.status,
      reasonCode: correlation.reasonCode,
      keyType: correlation.keyType,
      ...(typeof correlation.keyValue === "string" ? { keyValue: correlation.keyValue } : {}),
      contributingPlans: Array.from(session.contributingPlans).sort(),
    });
  }
  return results.sort((a, b) => a.correlationSessionId.localeCompare(b.correlationSessionId));
}

function validateSuiteManifest(input: unknown):
  | { ok: true; manifest: RuntimeSuiteManifest }
  | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return { ok: false, reasonCode: "runtime_suite_invalid", requiredUserAction: ["Set runtime suite JSON object."] };
  }
  if (typeof input.executionProfile !== "string" || input.executionProfile.trim().length === 0) {
    return { ok: false, reasonCode: "runtime_suite_invalid", requiredUserAction: ["Set non-empty executionProfile."] };
  }
  const suiteType = typeof input.suiteType === "string" ? input.suiteType.trim() : "regression";
  if (suiteType !== "regression") {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set executionProfiles[].suiteType to regression for execution_orchestration."],
    };
  }
  if (input.executionPolicy !== "stop_on_fail" && input.executionPolicy !== "continue_on_fail") {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set executionPolicy to stop_on_fail|continue_on_fail."],
    };
  }
  if (!Array.isArray(input.plans) || input.plans.length === 0) {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set non-empty plans[]."],
    };
  }
  const plans: RuntimeSuitePlanEntry[] = [];
  for (const raw of input.plans) {
    if (!isRecord(raw)) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[] entries as objects."],
      };
    }
    if (typeof raw.order !== "number" || !Number.isInteger(raw.order) || raw.order <= 0) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].order as positive integer."],
      };
    }
    if (typeof raw.planName !== "string" || raw.planName.trim().length === 0) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set non-empty plans[].planName."],
      };
    }
    if (isReplayScriptPath(raw.planName.trim())) {
      return {
        ok: false,
        reasonCode: "invalid_execution_path_replay_script",
        requiredUserAction: [
          "Use regression plan names only in executionProfiles[].plans[].planName; replay/export script paths are not allowed.",
        ],
      };
    }
    if (
      typeof raw.onFail !== "undefined" &&
      raw.onFail !== "inherit" &&
      raw.onFail !== "stop" &&
      raw.onFail !== "continue"
    ) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].onFail to inherit|stop|continue."],
      };
    }
    plans.push({
      order: raw.order,
      planName: raw.planName.trim(),
      ...(typeof raw.onFail === "string" ? { onFail: raw.onFail } : {}),
      ...(typeof raw.runtimeContextName === "string" && raw.runtimeContextName.trim().length > 0
        ? { runtimeContextName: raw.runtimeContextName.trim() }
        : {}),
      ...(isRecord(raw.providedContext) ? { providedContext: raw.providedContext } : {}),
    });
  }
  const orders = plans.map((entry) => entry.order).sort((a, b) => a - b);
  for (let i = 0; i < orders.length; i += 1) {
    if (orders[i] !== i + 1) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].order sequentially from 1..N."],
      };
    }
  }
  const runtimeConfig = isRecord(input.runtimeConfig)
    ? {
        ...(typeof input.runtimeConfig.requestTimeoutMs === "number"
          ? { requestTimeoutMs: input.runtimeConfig.requestTimeoutMs }
          : {}),
        ...(typeof input.runtimeConfig.retryMax === "number" ? { retryMax: input.runtimeConfig.retryMax } : {}),
      }
    : undefined;
  const scriptRefs = Array.isArray(input.scriptRefs)
    ? input.scriptRefs
        .map((entry): RuntimeSuiteScriptRef | null => {
          if (typeof entry === "string" && entry.trim().length > 0) {
            return { name: entry.trim() };
          }
          if (!isRecord(entry)) {
            return null;
          }
          if (typeof entry.name !== "string" || entry.name.trim().length === 0) {
            return null;
          }
          const phase = typeof entry.phase === "string" ? entry.phase.trim() : "";
          const phaseValue = isRuntimeSuiteScriptPhase(phase) ? phase : undefined;
          if (phase.length > 0 && !phaseValue) {
            return null;
          }
          if (phaseValue) {
            return {
              name: entry.name.trim(),
              phase: phaseValue,
            };
          }
          return { name: entry.name.trim() };
        })
        .filter((entry): entry is RuntimeSuiteScriptRef => entry !== null)
    : [];
  return {
    ok: true,
    manifest: {
      executionProfile: input.executionProfile.trim(),
      suiteType: "regression",
      ...(typeof input.runtimeContextName === "string" && input.runtimeContextName.trim().length > 0
        ? { runtimeContextName: input.runtimeContextName.trim() }
        : {}),
      executionPolicy: input.executionPolicy,
      ...(runtimeConfig ? { runtimeConfig } : {}),
      ...(scriptRefs.length > 0 ? { scriptRefs } : {}),
      plans,
    },
  };
}

async function readSuiteManifest(args: {
  workspaceRootAbs: string;
  projectName?: string;
  executionProfile: string;
}): Promise<{ ok: true; manifest: RuntimeSuiteManifest } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const projectName = path.basename(path.dirname(path.dirname(plansRootAbs)));
  const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  const parsed = await readProjectArtifact(projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${projectsFileAbs}.`],
  }));
  if (!parsed.ok) {
    return {
      ok: false,
      reasonCode: parsed.reasonCode,
      requiredUserAction: parsed.errors,
    };
  }
  const workspace = parsed.artifact.workspaces.find((entry) => entry.projectRoot === args.workspaceRootAbs);
  if (!workspace) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: ["Workspace entry not found for current projectRoot in projects.json."],
    };
  }
  const profiles = Array.isArray(workspace.executionProfiles) ? workspace.executionProfiles : [];
  const match = profiles.find((entry) => entry.executionProfile === args.executionProfile);
  if (!match) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [`Add executionProfiles entry '${args.executionProfile}' to projects.json.`],
    };
  }
  return validateSuiteManifest(match);
}

function isSuiteLevelPreflightBlocker(reasonCode: string | undefined): boolean {
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

export type ExecuteRegressionRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  executionProfile: string;
  mcpInvoke: ExecuteRegressionPlanWorkflowArgs["mcpInvoke"];
  suiteRunId?: string;
  startPlanOrder?: number;
  priorPlanRuns?: RuntimeSuiteRunResult["planRuns"];
  priorSuiteContext?: Record<string, unknown>;
  maxPlansPerCall?: number;
  orchestrationTimeoutBudgetMs?: number;
};

export async function executeRegressionRuntimeSuite(
  args: ExecuteRegressionRuntimeSuiteArgs,
): Promise<RuntimeSuiteRunResult | { status: "blocked"; reasonCode: string; requiredUserAction: string[] }> {
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
    (entry) => entry.status === "blocked" || (entry.status === "executed" && entry.runStatus === "blocked"),
  );
  let suiteLevelBlocked = planRuns.some(
    (entry) => entry.status === "blocked" && isSuiteLevelPreflightBlocker(entry.blockedReasonCode),
  );
  const orderedPlans = [...manifest.plans].sort((a, b) => a.order - b.order);
  const startPlanOrder =
    typeof args.startPlanOrder === "number" && Number.isInteger(args.startPlanOrder) && args.startPlanOrder > 0
      ? args.startPlanOrder
      : 1;
  if (startPlanOrder > orderedPlans.length + 1) {
    return {
      status: "blocked",
      reasonCode: "suite_progress_invalid",
      requiredUserAction: [`Persist nextPlanOrder within 1..${orderedPlans.length + 1} before resuming suite execution.`],
    };
  }
  const maxPlansPerCall =
    typeof args.maxPlansPerCall === "number" && Number.isInteger(args.maxPlansPerCall) && args.maxPlansPerCall > 0
      ? args.maxPlansPerCall
      : undefined;
  const plansRootAbs = await resolveRegressionPlansRootAbs(
    args.workspaceRootAbs,
    typeof args.projectName === "string" && args.projectName.trim().length > 0 ? args.projectName.trim() : undefined,
  );
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
    if (priorPlanRun?.runStatus === "in_progress" && typeof priorPlanRun.runId === "string" && !resumeState) {
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
      ...(run.runStatus === "blocked" ? resolveBlockedPlanDetail(run.executionResult as unknown as Record<string, unknown>) : {}),
      runId: run.runId,
    });
    if (run.runStatus === "in_progress") {
      nextPlanOrder = plan.order;
      break;
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
      requiredUserAction: ["Advance at least one plan per resumed call or increase maxPlansPerCall."],
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
      completedPlanCount: countCompletedPlanRuns(planRuns),
      ...(Object.keys(suiteProvidedContext).length > 0 ? { suiteContext: suiteProvidedContext } : {}),
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
