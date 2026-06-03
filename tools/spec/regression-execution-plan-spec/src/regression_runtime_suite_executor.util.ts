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
import type { CorrelationArtifact } from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
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

async function collectSuiteCorrelationSession(args: {
  runDirAbs: string;
  planName: string;
  sessions: Map<string, RuntimeSuiteCorrelationSession>;
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
  if (!(session.maxWindowMs > 0) && maxWindowMs > 0) {
    session.maxWindowMs = maxWindowMs;
  }
  if (!session.expectedFlow && expectedFlow) {
    session.expectedFlow = expectedFlow;
  }
  args.sessions.set(sessionId, session);
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
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [`Unable to read projects.json: ${projectsFileAbs}`],
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

export type ExecuteRegressionRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  executionProfile: string;
  mcpInvoke: ExecuteRegressionPlanWorkflowArgs["mcpInvoke"];
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
  const planRuns: RuntimeSuiteRunResult["planRuns"] = [];
  const suiteRunId = buildTimestampRunId(new Date(), 1);
  const correlationSessions = new Map<string, RuntimeSuiteCorrelationSession>();
  let hasFail = false;
  let hasBlocked = false;
  const orderedPlans = [...manifest.plans].sort((a, b) => a.order - b.order);
  let stop = false;
  for (const plan of orderedPlans) {
    if (stop) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    const run = await executeRegressionPlanWorkflow({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      planName: plan.planName,
      mcpInvoke: args.mcpInvoke,
      ...(manifest.runtimeConfig ? { runtimeConfigOverride: manifest.runtimeConfig } : {}),
      ...(plan.runtimeContextName || manifest.runtimeContextName
        ? { runtimeContextName: plan.runtimeContextName ?? manifest.runtimeContextName }
        : {}),
      executionProfileName: manifest.executionProfile,
      suiteRunId,
      ...(plan.providedContext ? { providedContext: plan.providedContext } : {}),
    });
    if (run.status === "blocked") {
      hasBlocked = true;
      planRuns.push({
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
    await collectSuiteCorrelationSession({
      runDirAbs: run.artifacts.runDirAbs,
      planName: plan.planName,
      sessions: correlationSessions,
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

  let status: RuntimeSuiteRunResult["status"] = "pass";
  if (manifest.executionPolicy === "continue_on_fail") {
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
  };
  const correlations = await writeSuiteCorrelationResults({
    sessions: correlationSessions,
    now: new Date(),
  });
  if (correlations.length > 0) {
    result.correlations = correlations;
  }
  return result;
}
