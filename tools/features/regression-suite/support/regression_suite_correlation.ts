/**
 * Regression Suite cross-plan correlation session support.
 */
import path from "node:path";
import { promises as fs } from "node:fs";

import type { RuntimeSuiteCorrelationResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import type { CorrelationArtifact } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { correlateEvents } from "../shared/regression_correlation";
import type {
  RuntimeSuiteCorrelationEvent,
  RuntimeSuiteCorrelationSession,
} from "../models/regression_suite.model";
export type { RuntimeSuiteCorrelationSession } from "../models/regression_suite.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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

const SUITE_CORRELATION_LAST_KEY_VALUE = "suite.correlation.last.keyValue";
const SUITE_CORRELATION_LAST_KEY_TYPE = "suite.correlation.last.keyType";
const SUITE_CORRELATION_LAST_SESSION_ID = "suite.correlation.last.correlationSessionId";
const SUITE_CORRELATION_LAST_SOURCE_PLAN = "suite.correlation.last.sourcePlanName";

function asCorrelationKeyType(value: unknown): "traceId" | "requestId" | "messageId" {
  return value === "requestId" ? "requestId" : value === "messageId" ? "messageId" : "traceId";
}

function asCanonicalCorrelationEvent(value: unknown): RuntimeSuiteCorrelationEvent | null {
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

function correlationEventsKeyValues(events: RuntimeSuiteCorrelationEvent[]): string[] {
  return Array.from(
    new Set(
      events
        .map((event) => event.keyValue)
        .filter((value): value is string => typeof value === "string" && value.trim().length > 0),
    ),
  ).sort();
}

function isSuiteContextTokenSafe(value: string): boolean {
  return /^[A-Za-z0-9_.-]+$/.test(value);
}

function resolveSuiteCorrelationSessionKeyValue(
  session: RuntimeSuiteCorrelationSession,
): string | undefined {
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

export async function collectSuiteCorrelationSession(args: {
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
    policy &&
    typeof policy.correlationSessionId === "string" &&
    policy.correlationSessionId.trim().length > 0
      ? policy.correlationSessionId.trim()
      : undefined;
  if (!policy || !sessionId || events.length === 0) return;

  const normalizedEvents = events
    .map((event) => asCanonicalCorrelationEvent(event))
    .filter((event): event is RuntimeSuiteCorrelationEvent => event !== null);
  if (normalizedEvents.length === 0) return;

  const keyType = asCorrelationKeyType(policy.keyType);
  const expectedFlow = Array.isArray(policy.expectedFlow)
    ? policy.expectedFlow.map((value) => String(value))
    : undefined;
  const maxWindowMs =
    typeof policy.maxWindowMs === "number" && Number.isFinite(policy.maxWindowMs)
      ? policy.maxWindowMs
      : 0;
  const session = args.sessions.get(sessionId) ?? {
    correlationSessionId: sessionId,
    keyType,
    ...(typeof policy.keyValue === "string" && policy.keyValue.trim().length > 0
      ? { keyValue: policy.keyValue }
      : {}),
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

export async function writeSuiteCorrelationResults(args: {
  sessions: Map<string, RuntimeSuiteCorrelationSession>;
  now: Date;
}): Promise<RuntimeSuiteCorrelationResult[]> {
  const results: RuntimeSuiteCorrelationResult[] = [];
  const sessionEntries = Array.from(args.sessions.values()).filter(
    (entry) => entry.contributingPlans.size > 1,
  );
  for (const session of sessionEntries) {
    const distinctKeyValues = correlationEventsKeyValues(session.events);
    const keyValue =
      typeof session.keyValue === "string" && session.keyValue.trim().length > 0
        ? session.keyValue
        : distinctKeyValues.length === 1
          ? distinctKeyValues[0]
          : undefined;

    const matched =
      typeof keyValue === "string" && keyValue.trim().length > 0
        ? correlateEvents(session.events, {
            keyType: session.keyType,
            keyValue,
            maxWindowMs: session.maxWindowMs,
            ...(session.expectedFlow ? { expectedFlow: session.expectedFlow } : {}),
          })
        : {
            status: "fail_closed" as const,
            reasonCode: "missing_correlation_key" as const,
            timeline: [],
          };

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
      reasonCode:
        matched.reasonCode === "ambiguous_correlation"
          ? "ambiguous_cross_plan_correlation"
          : matched.reasonCode,
      correlationSessionId: session.correlationSessionId,
      keyType: session.keyType,
      ...(keyValue ? { keyValue } : {}),
      window: {
        ...(typeof startEvent?.timestampEpochMs === "number"
          ? { startEpochMs: startEvent.timestampEpochMs }
          : {}),
        ...(typeof endEvent?.timestampEpochMs === "number"
          ? { endEpochMs: endEvent.timestampEpochMs }
          : {}),
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
