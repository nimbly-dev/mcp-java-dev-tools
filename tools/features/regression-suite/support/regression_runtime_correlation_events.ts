import { fetchJson } from "@tools-core/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@tools-core/safety";
import type { RuntimeSuiteCorrelationEvent } from "../models/regression_suite.model";

export type RuntimeCorrelationEventCursor = {
  events: RuntimeSuiteCorrelationEvent[];
  nextSequence: number;
  highWaterSequence: number;
  hasMore: boolean;
  budgetExceeded: boolean;
  contractValid: boolean;
  streamRuntimeInstanceId?: string;
  streamResetEpoch?: number;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asKeyType(value: unknown): RuntimeSuiteCorrelationEvent["keyType"] | null {
  return value === "requestId" || value === "messageId" || value === "traceId" ? value : null;
}

function normalizeRuntimeEvent(value: unknown): RuntimeSuiteCorrelationEvent | null {
  const event = asRecord(value);
  const keyType = asKeyType(event?.keyType);
  if (
    !event ||
    typeof event.eventId !== "string" ||
    typeof event.probeId !== "string" ||
    typeof event.runtimeInstanceId !== "string" ||
    typeof event.lineKey !== "string" ||
    typeof event.timestampEpochMs !== "number" ||
    !Number.isFinite(event.timestampEpochMs) ||
    typeof event.keyFingerprint !== "string" ||
    event.eventType !== "runtime_line_hit" ||
    !keyType
  ) {
    return null;
  }
  return {
    ...(typeof event.sequence === "number" &&
    Number.isInteger(event.sequence) &&
    event.sequence >= 0
      ? { sequence: event.sequence }
      : {}),
    ...(typeof event.lastSequence === "number" &&
    Number.isInteger(event.lastSequence) &&
    event.lastSequence >= 0
      ? { lastSequence: event.lastSequence }
      : {}),
    ...(typeof event.hitCount === "number" && Number.isInteger(event.hitCount) && event.hitCount > 0
      ? { hitCount: event.hitCount }
      : {}),
    ...(typeof event.firstTimestampEpochMs === "number" &&
    Number.isFinite(event.firstTimestampEpochMs)
      ? { firstTimestampEpochMs: event.firstTimestampEpochMs }
      : {}),
    ...(typeof event.correlationSessionId === "string"
      ? { correlationSessionId: event.correlationSessionId }
      : {}),
    ...(typeof event.correlationExecutionId === "string"
      ? { correlationExecutionId: event.correlationExecutionId }
      : {}),
    eventId: event.eventId,
    probeId: event.probeId,
    runtimeInstanceId: event.runtimeInstanceId,
    lineKey: event.lineKey,
    timestampEpochMs: event.timestampEpochMs,
    keyType,
    keyFingerprint: event.keyFingerprint,
    eventType: "runtime_line_hit",
  };
}

export async function readRuntimeCorrelationEvents(args: {
  baseUrl: string;
  sessionId: string;
  afterSequence?: number;
  limit?: number;
  maxEvents?: number;
  maxBytes?: number;
  maxDurationMs?: number;
  path?: string;
  timeoutMs?: number;
  headers?: Record<string, string>;
}): Promise<RuntimeCorrelationEventCursor> {
  const timeoutMs = clampInt(
    args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS,
    1_000,
    HARD_MAX_PROBE_TIMEOUT_MS,
  );
  const pageLimit = clampInt(args.limit ?? 256, 1, 1_000);
  const maxEvents = clampInt(args.maxEvents ?? 10_000, 1, 10_000);
  const maxBytes = clampInt(args.maxBytes ?? 1_048_576, 1_024, 1_048_576);
  const deadline = Date.now() + clampInt(args.maxDurationMs ?? timeoutMs, 1_000, 60_000);
  const events: RuntimeSuiteCorrelationEvent[] = [];
  let afterSequence = Math.max(0, args.afterSequence ?? 0);
  let nextSequence = afterSequence;
  let highWaterSequence = afterSequence;
  let hasMore = false;
  let budgetExceeded = false;
  let contractValid = true;
  let streamRuntimeInstanceId: string | undefined;
  let streamResetEpoch: number | undefined;
  while (true) {
    if (
      Date.now() > deadline ||
      events.length >= maxEvents ||
      JSON.stringify(events).length >= maxBytes
    ) {
      budgetExceeded = true;
      break;
    }
    const url = new URL(args.path ?? "/__probe/correlation/events", args.baseUrl);
    url.searchParams.set("sessionId", args.sessionId);
    url.searchParams.set("afterSequence", String(afterSequence));
    url.searchParams.set("limit", String(pageLimit));
    const response = await fetchJson(url.toString(), {
      method: "GET",
      timeoutMs,
      ...(args.headers ? { headers: args.headers } : {}),
    });
    const payload = asRecord(response.json);
    if (typeof payload?.streamRuntimeInstanceId === "string")
      streamRuntimeInstanceId = payload.streamRuntimeInstanceId;
    if (typeof payload?.streamResetEpoch === "number") streamResetEpoch = payload.streamResetEpoch;
    const rawEvents = Array.isArray(payload?.events) ? payload.events : [];
    const pageEvents = rawEvents
      .map(normalizeRuntimeEvent)
      .filter((event): event is RuntimeSuiteCorrelationEvent => event !== null);
    if (
      pageEvents.length !== rawEvents.length ||
      pageEvents.some(
        (event) =>
          event.correlationSessionId !== args.sessionId ||
          typeof event.correlationExecutionId !== "string" ||
          typeof event.lastSequence !== "number" ||
          typeof event.hitCount !== "number",
      )
    ) {
      contractValid = false;
      break;
    }
    nextSequence =
      typeof payload?.lastDeliveredSequence === "number" &&
      Number.isFinite(payload.lastDeliveredSequence)
        ? payload.lastDeliveredSequence
        : afterSequence;
    highWaterSequence =
      typeof payload?.highWaterSequence === "number" && Number.isFinite(payload.highWaterSequence)
        ? payload.highWaterSequence
        : nextSequence;
    hasMore = payload?.hasMore === true;
    events.push(...pageEvents);
    if (!hasMore || nextSequence <= afterSequence) break;
    afterSequence = nextSequence;
  }
  return {
    events,
    nextSequence,
    highWaterSequence,
    hasMore,
    budgetExceeded,
    contractValid,
    ...(streamRuntimeInstanceId ? { streamRuntimeInstanceId } : {}),
    ...(typeof streamResetEpoch === "number" ? { streamResetEpoch } : {}),
  };
}
