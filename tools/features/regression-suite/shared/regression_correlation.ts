import { createHash } from "node:crypto";

import type {
  CorrelationFailureReason,
  CorrelationInputEvent,
  CorrelationKeyType,
  CorrelationMatchResult,
  CorrelationPolicy,
} from "../models/regression_suite.model";
export type {
  CorrelationFailureReason,
  CorrelationInputEvent,
  CorrelationKeyType,
  CorrelationMatchResult,
  CorrelationPolicy,
} from "../models/regression_suite.model";

function normalizeString(value: string): string {
  return value.trim();
}

function isRuntimeLineHit(event: CorrelationInputEvent): boolean {
  return event.eventType === "runtime_line_hit";
}

function keyFingerprint(value: string): string {
  return `sha256:${createHash("sha256").update(value, "utf8").digest("hex")}`;
}

function sortEvents(
  events: CorrelationInputEvent[],
  expectedFlow?: string[],
): CorrelationInputEvent[] {
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
    if (typeof current === "number" && typeof previous === "number" && current < previous)
      return true;
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
      (event.keyType === policy.keyType &&
        typeof event.keyValue === "string" &&
        normalizeString(event.keyValue) === keyValue) ||
      (event.keyType === policy.keyType && event.keyFingerprint === keyFingerprint(keyValue)),
  );

  if (policy.runtimeEvidenceRequired) {
    const runtimeEvents = events.filter(isRuntimeLineHit);
    if (runtimeEvents.length === 0) {
      return {
        status: "fail_closed",
        reasonCode: "correlation_key_not_observed",
        timeline: [],
      };
    }
    if (
      runtimeEvents.some(
        (event) =>
          typeof event.runtimeInstanceId !== "string" ||
          event.runtimeInstanceId.trim().length === 0,
      )
    ) {
      return {
        status: "fail_closed",
        reasonCode: "correlation_runtime_identity_missing",
        timeline: [],
      };
    }
    const scopedRuntimeEvents = policy.runtimeProbeIds?.length
      ? runtimeEvents.filter((event) => policy.runtimeProbeIds?.includes(event.probeId))
      : runtimeEvents;
    if (scopedRuntimeEvents.length === 0) {
      return {
        status: "fail_closed",
        reasonCode: "correlation_probe_scope_mismatch",
        timeline: [],
      };
    }
    const instanceScopedRuntimeEvents = policy.runtimeInstanceIds?.length
      ? scopedRuntimeEvents.filter(
          (event) =>
            typeof event.runtimeInstanceId === "string" &&
            policy.runtimeInstanceIds?.includes(event.runtimeInstanceId),
        )
      : scopedRuntimeEvents;
    if (instanceScopedRuntimeEvents.length === 0) {
      return {
        status: "fail_closed",
        reasonCode: "correlation_probe_scope_mismatch",
        timeline: [],
      };
    }
    const scopedCandidates = instanceScopedRuntimeEvents.filter(
      (event) =>
        (!policy.runtimeLineKeys?.length ||
          (typeof event.lineKey === "string" && policy.runtimeLineKeys.includes(event.lineKey))) &&
        (!policy.runtimeExecutionId ||
          event.correlationExecutionId === policy.runtimeExecutionId) &&
        event.keyType === policy.keyType &&
        ((typeof event.keyValue === "string" && normalizeString(event.keyValue) === keyValue) ||
          event.keyFingerprint === keyFingerprint(keyValue)),
    );
    if (scopedCandidates.length === 0) {
      return {
        status: "fail_closed",
        reasonCode: "correlation_key_not_observed",
        timeline: [],
      };
    }
    candidates.splice(0, candidates.length, ...scopedCandidates);
  }

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

  const duplicatesByIdentity = new Map<string, number>();
  for (const event of sorted) {
    const identity = event.runtimeInstanceId
      ? `${event.probeId}:${event.runtimeInstanceId}`
      : event.eventType === "runtime_line_hit"
        ? `${event.probeId}:${event.eventId}`
        : event.probeId;
    duplicatesByIdentity.set(identity, (duplicatesByIdentity.get(identity) ?? 0) + 1);
  }
  const runtimeIdentities = new Set(
    sorted
      .filter(isRuntimeLineHit)
      .map((event) => event.runtimeInstanceId)
      .filter((value): value is string => typeof value === "string" && value.length > 0),
  );
  if (
    runtimeIdentities.size > 1 ||
    Array.from(duplicatesByIdentity.values()).some(
      (count) => count > 1 && !sorted.some((event) => event.eventType === "runtime_line_hit"),
    )
  ) {
    return { status: "fail_closed", reasonCode: "ambiguous_correlation", timeline: sorted };
  }

  if (Array.isArray(policy.expectedFlow) && policy.expectedFlow.length > 0) {
    if (hasFlowViolation(sorted, policy.expectedFlow)) {
      return { status: "fail_closed", reasonCode: "flow_expectation_mismatch", timeline: sorted };
    }
  }

  return { status: "ok", reasonCode: "ok", timeline: sorted };
}
