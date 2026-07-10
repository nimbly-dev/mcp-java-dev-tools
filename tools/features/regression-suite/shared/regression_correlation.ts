export type CorrelationKeyType = "traceId" | "requestId" | "messageId";
export type CorrelationFailureReason =
  | "missing_correlation_key"
  | "window_exceeded"
  | "no_matching_events"
  | "ambiguous_correlation"
  | "flow_expectation_mismatch";

export type CorrelationInputEvent = {
  eventId: string;
  probeId: string;
  timestampEpochMs: number;
  keyType: CorrelationKeyType;
  keyValue?: string;
  lineKey?: string;
};

export type CorrelationPolicy = {
  keyType: CorrelationKeyType;
  keyValue: string;
  maxWindowMs: number;
  expectedFlow?: string[];
};

export type CorrelationMatchResult =
  | {
      status: "ok";
      timeline: CorrelationInputEvent[];
      reasonCode: "ok";
    }
  | {
      status: "fail_closed";
      timeline: CorrelationInputEvent[];
      reasonCode: CorrelationFailureReason;
    };

function normalizeString(value: string): string {
  return value.trim();
}

function sortEvents(events: CorrelationInputEvent[], expectedFlow?: string[]): CorrelationInputEvent[] {
  const flowIndex = new Map<string, number>();
  if (Array.isArray(expectedFlow)) {
    expectedFlow.forEach((probeId, idx) => flowIndex.set(probeId, idx));
  }
  return [...events].sort((a, b) => {
    if (a.timestampEpochMs !== b.timestampEpochMs) return a.timestampEpochMs - b.timestampEpochMs;
    const aFlow = flowIndex.has(a.probeId) ? flowIndex.get(a.probeId)! : Number.MAX_SAFE_INTEGER;
    const bFlow = flowIndex.has(b.probeId) ? flowIndex.get(b.probeId)! : Number.MAX_SAFE_INTEGER;
    if (aFlow !== bFlow) return aFlow - bFlow;
    const lhs = `${a.probeId}:${a.lineKey ?? ""}:${a.eventId}`;
    const rhs = `${b.probeId}:${b.lineKey ?? ""}:${b.eventId}`;
    return lhs.localeCompare(rhs);
  });
}

function hasFlowViolation(timeline: CorrelationInputEvent[], expectedFlow: string[]): boolean {
  const indexes = timeline
    .map((event) => expectedFlow.indexOf(event.probeId))
    .filter((idx): idx is number => idx >= 0);
  for (let i = 1; i < indexes.length; i += 1) {
    const current = indexes[i];
    const previous = indexes[i - 1];
    if (typeof current === "number" && typeof previous === "number" && current < previous) return true;
  }
  return false;
}

export function correlateEvents(
  events: CorrelationInputEvent[],
  policy: CorrelationPolicy,
): CorrelationMatchResult {
  const keyValue = normalizeString(policy.keyValue);
  if (!keyValue) {
    return { status: "fail_closed", reasonCode: "missing_correlation_key", timeline: [] };
  }
  if (!(policy.maxWindowMs > 0)) {
    return { status: "fail_closed", reasonCode: "window_exceeded", timeline: [] };
  }

  const candidates = events.filter(
    (event) =>
      event.keyType === policy.keyType &&
      typeof event.keyValue === "string" &&
      normalizeString(event.keyValue) === keyValue,
  );

  if (candidates.length === 0) {
    return { status: "fail_closed", reasonCode: "no_matching_events", timeline: [] };
  }

  const sorted = sortEvents(candidates, policy.expectedFlow);
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (!first || !last) {
    return { status: "fail_closed", reasonCode: "no_matching_events", timeline: [] };
  }
  const windowStart = first.timestampEpochMs;
  const windowEnd = last.timestampEpochMs;
  if (windowEnd - windowStart > policy.maxWindowMs) {
    return { status: "fail_closed", reasonCode: "window_exceeded", timeline: sorted };
  }

  const duplicatesByProbe = new Map<string, number>();
  for (const event of sorted) {
    duplicatesByProbe.set(event.probeId, (duplicatesByProbe.get(event.probeId) ?? 0) + 1);
  }
  if (Array.from(duplicatesByProbe.values()).some((count) => count > 1)) {
    return { status: "fail_closed", reasonCode: "ambiguous_correlation", timeline: sorted };
  }

  if (Array.isArray(policy.expectedFlow) && policy.expectedFlow.length > 0) {
    if (hasFlowViolation(sorted, policy.expectedFlow)) {
      return { status: "fail_closed", reasonCode: "flow_expectation_mismatch", timeline: sorted };
    }
  }

  return { status: "ok", reasonCode: "ok", timeline: sorted };
}
