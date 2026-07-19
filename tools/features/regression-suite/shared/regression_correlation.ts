import { createHash } from "node:crypto";

import type {
  CorrelationFailureReason,
  CorrelationInputEvent,
  CorrelationKeyType,
  CorrelationMatchResult,
  CorrelationPolicy,
} from "../models/regression_suite.model";
export type {
  CorrelationDiagnostics,
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

function isSyntheticBoundConsumerEvidence(event: CorrelationInputEvent): boolean {
  return event.eventType === "consumer_entry" || event.eventType === "consumer_listener";
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

function findFlowSubsequenceFailure(
  timeline: CorrelationInputEvent[],
  expectedFlow: string[],
): number | undefined {
  let timelineIndex = 0;
  for (let flowIndex = 0; flowIndex < expectedFlow.length; flowIndex += 1) {
    const expectedProbeId = expectedFlow[flowIndex];
    while (
      timelineIndex < timeline.length &&
      timeline[timelineIndex]?.probeId !== expectedProbeId
    ) {
      timelineIndex += 1;
    }
    if (timelineIndex >= timeline.length) return flowIndex;
    timelineIndex += 1;
  }
  return undefined;
}

function findMissingFlowStage(
  timeline: CorrelationInputEvent[],
  expectedFlow: string[],
): { missingProbeIds: string[]; firstUnsatisfiedFlowIndex?: number } {
  const observedCounts = new Map<string, number>();
  for (const event of timeline) {
    observedCounts.set(event.probeId, (observedCounts.get(event.probeId) ?? 0) + 1);
  }
  const requiredCounts = new Map<string, number>();
  for (const probeId of expectedFlow) {
    requiredCounts.set(probeId, (requiredCounts.get(probeId) ?? 0) + 1);
  }
  const missingProbeIds = Array.from(requiredCounts.keys()).filter(
    (probeId) => (observedCounts.get(probeId) ?? 0) < requiredCounts.get(probeId)!,
  );
  if (missingProbeIds.length === 0) return { missingProbeIds: [] };
  const firstUnsatisfiedFlowIndex = findFlowSubsequenceFailure(timeline, expectedFlow);
  if (typeof firstUnsatisfiedFlowIndex !== "number") {
    return { missingProbeIds };
  }
  return {
    missingProbeIds,
    firstUnsatisfiedFlowIndex,
  };
}

function flowDiagnostics(
  timeline: CorrelationInputEvent[],
  expectedFlow: string[],
): {
  expectedFlow: string[];
  observedProbeIds: string[];
  missingProbeIds: string[];
  firstUnsatisfiedFlowIndex?: number;
} {
  const missing = findMissingFlowStage(timeline, expectedFlow);
  return {
    expectedFlow,
    observedProbeIds: timeline.map((event) => event.probeId),
    ...missing,
  };
}

export function correlateEvents(
  events: CorrelationInputEvent[],
  policy: CorrelationPolicy,
): CorrelationMatchResult {
  const expectedFlow = policy.expectedFlow ?? [];
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
        reasonCode: candidates.some(isSyntheticBoundConsumerEvidence)
          ? "correlation_context_not_propagated"
          : candidates.length > 0 && expectedFlow.length > 1
            ? "missing_expected_flow_event"
            : "correlation_key_not_observed",
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
    const scopedProbeIds = policy.probeIds?.length ? policy.probeIds : policy.runtimeProbeIds;
    const scopedRuntimeEvents = scopedProbeIds?.length
      ? runtimeEvents.filter((event) => scopedProbeIds.includes(event.probeId))
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
    const scopedCorrelationEvents = instanceScopedRuntimeEvents.filter((event) =>
      candidates.includes(event),
    );
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
      const downstreamRuntimeObserved = instanceScopedRuntimeEvents.some((event) =>
        expectedFlow.slice(1).includes(event.probeId),
      );
      return {
        status: "fail_closed",
        reasonCode:
          scopedCorrelationEvents.length > 0
            ? "correlation_context_not_propagated"
            : downstreamRuntimeObserved || expectedFlow.length <= 1
              ? "correlation_key_not_observed"
              : "missing_expected_flow_event",
        timeline: [],
      };
    }
    const planCandidates = candidates.filter((event) => !isRuntimeLineHit(event));
    candidates.splice(0, candidates.length, ...planCandidates, ...scopedCandidates);
  }

  if (candidates.length === 0) {
    return { status: "fail_closed", reasonCode: "no_matching_events", timeline: [] };
  }

  const sorted = sortEvents(candidates, expectedFlow);
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
  const expectedFlowConfigured =
    Array.isArray(policy.expectedFlow) && policy.expectedFlow.length > 0;
  if (
    runtimeIdentities.size > 1 ||
    Array.from(duplicatesByIdentity.values()).some(
      (count) =>
        count > 1 &&
        !sorted.some((event) => event.eventType === "runtime_line_hit") &&
        !expectedFlowConfigured,
    )
  ) {
    return { status: "fail_closed", reasonCode: "ambiguous_correlation", timeline: sorted };
  }

  if (Array.isArray(policy.expectedFlow) && policy.expectedFlow.length > 0) {
    const diagnostics = flowDiagnostics(sorted, policy.expectedFlow);
    if (diagnostics.missingProbeIds.length > 0) {
      return {
        status: "fail_closed",
        reasonCode: "missing_expected_flow_event",
        timeline: sorted,
        ...diagnostics,
      };
    }
    const flowSubsequenceFailureIndex = findFlowSubsequenceFailure(sorted, policy.expectedFlow);
    if (typeof flowSubsequenceFailureIndex === "number") {
      return {
        status: "fail_closed",
        reasonCode: "flow_expectation_mismatch",
        timeline: sorted,
        ...diagnostics,
        firstUnsatisfiedFlowIndex: flowSubsequenceFailureIndex,
      };
    }
    return { status: "ok", reasonCode: "ok", timeline: sorted, ...diagnostics };
  }

  return { status: "ok", reasonCode: "ok", timeline: sorted };
}
