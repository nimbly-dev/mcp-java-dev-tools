import { promises as fs } from "node:fs";
import { readdirSync, statSync } from "node:fs";
import path from "node:path";

import type {
  CorrelationIndexRebuildResult,
  CorrelationArtifact,
  RegressionRunArtifactsWriteResult,
  RegressionRunExecutionResult,
  RegressionRunWatcherOutcome,
  RegressionRunWatcherResult,
  RegressionRunWatcherResultStatus,
  RegressionWatcherReasonCode,
  RegressionRunWatcherWaitSummary,
  WatcherExecutionEvidence,
  WriteRegressionRunArtifactsInput,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { correlateEvents } from "@tools-regression-execution-plan-spec/regression_correlation.util";
import {
  buildResolvedSecretRedactionMeta,
  sanitizeSuitePersistedContext,
} from "@tools-regression-execution-plan-spec/suite_context_redaction.util";

export type {
  CorrelationIndexRebuildResult,
  RegressionPlanReference,
  RegressionRunArtifactsWriteResult,
  RegressionRunExecutionResult,
  RegressionRunStatus,
  WriteRegressionRunArtifactsInput,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";

const RUN_ID_PATTERN =
  /^(?:\d{2}-\d{2}-\d{4}-\d{2}-\d{2}-\d{2}(?:AM|PM)|\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z_\d{2}|\d{10,})$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stripRedundantResolvedContextFields(
  input: Record<string, unknown>,
): Record<string, unknown> {
  const next = { ...input };
  delete next.scope;
  return next;
}

function normalizeDiscoveryEvidence(discovery: unknown): unknown {
  if (!isRecord(discovery)) return undefined;
  const attempted = discovery.attempted === true;
  const status = discovery.status === "blocked" ? "blocked" : "resolved";
  const reasonCode = typeof discovery.reasonCode === "string" ? discovery.reasonCode : "ok";
  const rawOutcomes = Array.isArray(discovery.outcomes) ? discovery.outcomes : [];

  const outcomes = rawOutcomes
    .filter((entry) => isRecord(entry))
    .map((entry) => {
      const output: Record<string, unknown> = {
        key: String(entry.key ?? ""),
        source: entry.source === "runtime_context" ? "runtime_context" : "datasource",
        outcome: String(entry.outcome ?? "blocked_runtime_error"),
        reasonCode: String(entry.reasonCode ?? "discovery_adapter_failure"),
      };
      if (typeof entry.candidateCount === "number") output.candidateCount = entry.candidateCount;
      if (typeof entry.sourceRef === "string") output.sourceRef = entry.sourceRef;
      return output;
    })
    .sort((a, b) => {
      const lhs = `${String(a.key)}:${String(a.source)}`;
      const rhs = `${String(b.key)}:${String(b.source)}`;
      return lhs.localeCompare(rhs);
    });

  return {
    attempted,
    status,
    reasonCode,
    outcomes,
  };
}

function normalizeEvidencePayload(evidence: Record<string, unknown>): Record<string, unknown> {
  const normalized = { ...evidence };
  if ("discovery" in evidence) {
    const discovery = normalizeDiscoveryEvidence(evidence.discovery);
    if (typeof discovery === "undefined") {
      delete normalized.discovery;
    } else {
      normalized.discovery = discovery;
    }
  }
  if ("watcherExecutions" in evidence) {
    const watcherExecutions = normalizeWatcherExecutionEvidence(evidence.watcherExecutions);
    if (watcherExecutions.length === 0) {
      delete normalized.watcherExecutions;
    } else {
      normalized.watcherExecutions = watcherExecutions;
    }
  }
  return normalized;
}

function normalizeExecutionResultPayload(
  executionResult: RegressionRunExecutionResult,
): RegressionRunExecutionResult {
  if (typeof executionResult.watchers === "undefined") {
    return executionResult;
  }
  if (!Array.isArray(executionResult.watchers)) {
    throw new Error("watcher_execution_result_invalid");
  }
  return {
    ...executionResult,
    watchers: executionResult.watchers
      .map((watcher) => normalizeExecutionWatcherResult(watcher))
      .map((watcher) => {
        if (!watcher) {
          throw new Error("watcher_execution_result_invalid");
        }
        return watcher;
      }),
  };
}

function normalizeExecutionWatcherResult(
  watcher: RegressionRunWatcherResult,
): RegressionRunWatcherResult | null {
  const id = typeof watcher.id === "string" && watcher.id.trim().length > 0 ? watcher.id : null;
  const dependencyStepOrder =
    typeof watcher.dependencyStepOrder === "number" && Number.isFinite(watcher.dependencyStepOrder)
      ? watcher.dependencyStepOrder
      : null;
  const providerType =
    typeof watcher.providerType === "string" && watcher.providerType.trim().length > 0 ? watcher.providerType : null;
  const status = normalizeExecutionWatcherStatus(watcher.status);
  const reasonCode = normalizeWatcherReasonCode(watcher.reasonCode);
  const outcome = normalizeExecutionWatcherOutcome({
    outcome: watcher.outcome,
    reasonCode,
  });
  const attemptCount =
    typeof watcher.attemptCount === "number" && Number.isFinite(watcher.attemptCount) ? watcher.attemptCount : null;
  const durationMs =
    typeof watcher.durationMs === "number" && Number.isFinite(watcher.durationMs) ? watcher.durationMs : null;
  const waitPolicy = normalizeWatcherWaitPolicy(watcher.waitPolicy);
  if (
    !id ||
    dependencyStepOrder === null ||
    !providerType ||
    !status ||
    !outcome ||
    !reasonCode ||
    attemptCount === null ||
    durationMs === null ||
    !waitPolicy
  ) {
    return null;
  }

  return {
    ...watcher,
    id,
    dependencyStepOrder,
    providerType,
    status,
    outcome,
    attemptCount,
    durationMs,
    waitPolicy,
    reasonCode,
    ...(isRecord(watcher.lastObservation) ? { lastObservation: watcher.lastObservation } : {}),
    ...(Array.isArray(watcher.assertions) ? { assertions: watcher.assertions } : {}),
    ...(Array.isArray(watcher.attempts) ? { attempts: watcher.attempts } : {}),
    ...(isRecord(watcher.reasonMeta) ? { reasonMeta: watcher.reasonMeta } : {}),
  };
}

function normalizeWatcherExecutionEvidence(
  watcherExecutions: unknown,
): WatcherExecutionEvidence[] {
  if (typeof watcherExecutions === "undefined") {
    return [];
  }
  if (!Array.isArray(watcherExecutions)) {
    throw new Error("watcher_execution_evidence_invalid");
  }
  return watcherExecutions
    .map((entry) => normalizeWatcherExecutionEvidenceEntry(entry))
    .map((entry) => {
      if (!entry) {
        throw new Error("watcher_execution_evidence_invalid");
      }
      return entry;
    })
    .sort((lhs, rhs) => {
      if (lhs.dependencyStepOrder !== rhs.dependencyStepOrder) {
        return lhs.dependencyStepOrder - rhs.dependencyStepOrder;
      }
      return lhs.id.localeCompare(rhs.id);
    });
}

function normalizeWatcherExecutionEvidenceEntry(
  entry: unknown,
): WatcherExecutionEvidence | null {
  if (!isRecord(entry)) {
    return null;
  }
  const id = typeof entry.id === "string" && entry.id.trim().length > 0 ? entry.id : null;
  const dependencyStepOrder =
    typeof entry.dependencyStepOrder === "number" && Number.isFinite(entry.dependencyStepOrder)
      ? entry.dependencyStepOrder
      : null;
  const providerType =
    typeof entry.providerType === "string" && entry.providerType.trim().length > 0 ? entry.providerType : null;
  const attemptCount =
    typeof entry.attemptCount === "number" && Number.isFinite(entry.attemptCount) ? entry.attemptCount : null;
  const durationMs =
    typeof entry.durationMs === "number" && Number.isFinite(entry.durationMs) ? entry.durationMs : null;
  const reasonCode = normalizeWatcherReasonCode(entry.reasonCode);
  const waitPolicy = normalizeWatcherWaitPolicy(entry.waitPolicy);
  const status = normalizeWatcherExecutionEvidenceStatus({
    status: entry.status,
    outcome: entry.outcome,
    reasonCode,
  });
  const outcome = normalizeWatcherExecutionEvidenceOutcome({
    outcome: entry.outcome,
    reasonCode,
  });

  if (
    !id ||
    dependencyStepOrder === null ||
    !providerType ||
    attemptCount === null ||
    durationMs === null ||
    !reasonCode ||
    !waitPolicy ||
    !status ||
    !outcome
  ) {
    return null;
  }

  return {
    id,
    dependencyStepOrder,
    providerType,
    status,
    outcome,
    attemptCount,
    durationMs,
    reasonCode,
    waitPolicy,
    ...(isRecord(entry.lastObservation) ? { lastObservation: entry.lastObservation } : {}),
    ...(Array.isArray(entry.attempts) ? { attempts: entry.attempts } : {}),
    ...(Array.isArray(entry.assertions) ? { assertions: entry.assertions } : {}),
    ...(isRecord(entry.reasonMeta) ? { reasonMeta: entry.reasonMeta } : {}),
  };
}

function normalizeWatcherReasonCode(value: unknown): RegressionWatcherReasonCode | undefined {
  if (value === "watcher_verified") return "watcher_verified";
  if (value === "watcher_timeout") return "watcher_timeout";
  if (value === "watcher_target_unreachable") return "watcher_target_unreachable";
  if (value === "watcher_expectation_failed") return "watcher_expectation_failed";
  if (value === "watcher_configuration_invalid") return "watcher_configuration_invalid";
  if (value === "watcher_dependency_invalid") return "watcher_dependency_invalid";
  if (value === "ok") return "watcher_verified";
  if (value === "watcher_timeout_exceeded") return "watcher_timeout";
  if (value === "watcher_expectation_not_satisfied") return "watcher_expectation_failed";
  if (value === "watcher_dependency_not_satisfied") return "watcher_dependency_invalid";
  if (
    value === "watcher_runtime_configuration_invalid" ||
    value === "watcher_wait_policy_unresolved" ||
    value === "watcher_provider_not_supported" ||
    value === "watcher_response_normalization_failed"
  ) {
    return "watcher_configuration_invalid";
  }
  return undefined;
}

function normalizeWatcherWaitPolicy(value: unknown): RegressionRunWatcherWaitSummary | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  return {
    ...(typeof value.timeoutMs === "number" && Number.isFinite(value.timeoutMs) ? { timeoutMs: value.timeoutMs } : {}),
    timeoutSource:
      value.timeoutSource === "watcher_override" ||
      value.timeoutSource === "project_default" ||
      value.timeoutSource === "unresolved"
        ? value.timeoutSource
        : "unresolved",
    ...(typeof value.retryMax === "number" && Number.isFinite(value.retryMax) ? { retryMax: value.retryMax } : {}),
    retrySource:
      value.retrySource === "watcher_override" ||
      value.retrySource === "project_default" ||
      value.retrySource === "unresolved"
        ? value.retrySource
        : "unresolved",
    ...(typeof value.pollIntervalMs === "number" && Number.isFinite(value.pollIntervalMs)
      ? { pollIntervalMs: value.pollIntervalMs }
      : {}),
  };
}

function normalizeExecutionWatcherStatus(value: unknown): RegressionRunWatcherResultStatus | undefined {
  if (
    value === "pass" ||
    value === "fail_assertion" ||
    value === "blocked_dependency" ||
    value === "blocked_runtime"
  ) {
    return value;
  }
  return undefined;
}

function normalizeExecutionWatcherOutcome(args: {
  outcome: unknown;
  reasonCode: RegressionWatcherReasonCode | undefined;
}): RegressionRunWatcherOutcome | undefined {
  if (
    args.outcome === "verified" ||
    args.outcome === "failed_expectation" ||
    args.outcome === "timed_out" ||
    args.outcome === "blocked"
  ) {
    return args.outcome;
  }
  if (args.outcome === "timeout") {
    return "timed_out";
  }
  if (args.outcome === "expectation_failed") {
    return "failed_expectation";
  }
  if (
    args.outcome === "target_unreachable" ||
    args.outcome === "configuration_invalid" ||
    args.outcome === "dependency_invalid"
  ) {
    return "blocked";
  }
  if (args.reasonCode === "watcher_verified") return "verified";
  if (args.reasonCode === "watcher_expectation_failed") return "failed_expectation";
  if (args.reasonCode === "watcher_timeout") return "timed_out";
  if (
    args.reasonCode === "watcher_target_unreachable" ||
    args.reasonCode === "watcher_configuration_invalid" ||
    args.reasonCode === "watcher_dependency_invalid"
  ) {
    return "blocked";
  }
  return undefined;
}

function normalizeWatcherExecutionEvidenceStatus(args: {
  status: unknown;
  outcome: unknown;
  reasonCode: RegressionWatcherReasonCode | undefined;
}): WatcherExecutionEvidence["status"] | undefined {
  if (args.status === "ok" || args.status === "fail_closed" || args.status === "timed_out") {
    return args.status;
  }
  if (args.outcome === "timed_out" || args.outcome === "timeout" || args.reasonCode === "watcher_timeout") {
    return "timed_out";
  }
  if (args.status === "pass") {
    return "ok";
  }
  if (
    args.status === "fail_assertion" ||
    args.status === "fail_http" ||
    args.status === "blocked_runtime" ||
    args.status === "blocked_dependency"
  ) {
    return "fail_closed";
  }
  return undefined;
}

function normalizeWatcherExecutionEvidenceOutcome(args: {
  outcome: unknown;
  reasonCode: RegressionWatcherReasonCode | undefined;
}): WatcherExecutionEvidence["outcome"] | undefined {
  if (
    args.outcome === "verified" ||
    args.outcome === "timeout" ||
    args.outcome === "target_unreachable" ||
    args.outcome === "expectation_failed" ||
    args.outcome === "configuration_invalid" ||
    args.outcome === "dependency_invalid"
  ) {
    return args.outcome;
  }
  if (args.outcome === "timed_out") {
    return "timeout";
  }
  if (args.outcome === "failed_expectation") {
    return "expectation_failed";
  }
  if (args.outcome === "blocked") {
    if (args.reasonCode === "watcher_target_unreachable") return "target_unreachable";
    if (args.reasonCode === "watcher_dependency_invalid") return "dependency_invalid";
    return "configuration_invalid";
  }
  if (args.reasonCode === "watcher_verified") return "verified";
  if (args.reasonCode === "watcher_timeout") return "timeout";
  if (args.reasonCode === "watcher_target_unreachable") return "target_unreachable";
  if (args.reasonCode === "watcher_expectation_failed") return "expectation_failed";
  if (args.reasonCode === "watcher_configuration_invalid") return "configuration_invalid";
  if (args.reasonCode === "watcher_dependency_invalid") return "dependency_invalid";
  return undefined;
}

function normalizeCorrelationPayload(correlation: CorrelationArtifact): Record<string, unknown> {
  const timeline = [...(correlation.timeline ?? [])].sort((a, b) => {
    if (a.timestampEpochMs !== b.timestampEpochMs) return a.timestampEpochMs - b.timestampEpochMs;
    return `${a.probeId}:${a.eventId}`.localeCompare(`${b.probeId}:${b.eventId}`);
  });

  return {
    status: correlation.status,
    reasonCode: correlation.reasonCode,
    ...(isRecord(correlation.reasonMeta) ? { reasonMeta: correlation.reasonMeta } : {}),
    ...(typeof correlation.correlationSessionId === "string"
      ? { correlationSessionId: correlation.correlationSessionId }
      : {}),
    keyType: correlation.keyType,
    ...(typeof correlation.keyValue === "string" ? { keyValue: correlation.keyValue } : {}),
    window: correlation.window,
    ...(Array.isArray(correlation.expectedFlow) ? { expectedFlow: correlation.expectedFlow } : {}),
    timeline,
    ...(Array.isArray(correlation.evidenceRefs) ? { evidenceRefs: correlation.evidenceRefs } : {}),
    ...(typeof correlation.generatedAtEpochMs === "number"
      ? { generatedAtEpochMs: correlation.generatedAtEpochMs }
      : {}),
  };
}

function asCorrelationKeyType(value: unknown): "traceId" | "requestId" | "messageId" {
  return value === "requestId" ? "requestId" : value === "messageId" ? "messageId" : "traceId";
}

function toCorrelationArtifactFromEvidence(args: {
  evidence: Record<string, unknown>;
  resolvedContext: Record<string, unknown>;
  now: Date;
}): CorrelationArtifact | undefined {
  const policyRaw = args.evidence.correlationPolicy;
  const eventsRaw = args.evidence.correlationEvents;
  if (!isRecord(policyRaw) || !Array.isArray(eventsRaw)) return undefined;

  const keyType = asCorrelationKeyType(policyRaw.keyType);
  const maxWindowMs =
    typeof policyRaw.maxWindowMs === "number" && Number.isFinite(policyRaw.maxWindowMs)
      ? policyRaw.maxWindowMs
      : 0;
  const expectedFlow = Array.isArray(policyRaw.expectedFlow)
    ? policyRaw.expectedFlow.map((value) => String(value))
    : undefined;

  const keyValueRaw = policyRaw.keyValue;
  const keyFromContextPath =
    typeof policyRaw.keyValueContextPath === "string" ? policyRaw.keyValueContextPath : undefined;
  const keyFromContext =
    keyFromContextPath && typeof args.resolvedContext[keyFromContextPath] !== "undefined"
      ? String(args.resolvedContext[keyFromContextPath])
      : undefined;
  const keyValue = typeof keyValueRaw === "string" && keyValueRaw.trim().length > 0 ? keyValueRaw : keyFromContext;
  const keySourceType = typeof policyRaw.keySourceType === "string" ? policyRaw.keySourceType : undefined;
  const keySourcePath = typeof policyRaw.keySourcePath === "string" ? policyRaw.keySourcePath : undefined;
  const keyExtractionReasonCode =
    policyRaw.keyExtractionReasonCode === "correlation_key_extraction_failed"
      ? "correlation_key_extraction_failed"
      : undefined;

  const correlationEvents = eventsRaw
    .filter((entry) => isRecord(entry))
    .map((entry) => {
      const event = entry as Record<string, unknown>;
      return {
        eventId: String(event.eventId ?? ""),
        probeId: String(event.probeId ?? ""),
        timestampEpochMs: Number(event.timestampEpochMs ?? 0),
        keyType: asCorrelationKeyType(event.keyType),
        ...(typeof event.keyValue === "string" ? { keyValue: event.keyValue } : {}),
        ...(typeof event.lineKey === "string" ? { lineKey: event.lineKey } : {}),
      };
    })
    .filter((event) => event.eventId && event.probeId && Number.isFinite(event.timestampEpochMs));

  if (typeof keyValue !== "string" || keyValue.trim().length === 0) {
    return {
      status: "fail_closed",
      reasonCode: keyExtractionReasonCode ?? "missing_correlation_key",
      ...(keyExtractionReasonCode && (keySourceType || keySourcePath)
        ? {
            reasonMeta: {
              ...(keySourceType ? { sourceType: keySourceType } : {}),
              ...(keySourcePath ? { sourcePath: keySourcePath } : {}),
            },
          }
        : {}),
      keyType,
      window: { maxWindowMs: maxWindowMs > 0 ? maxWindowMs : 0 },
      timeline: [],
      generatedAtEpochMs: args.now.getTime(),
    };
  }

  const matched = correlateEvents(correlationEvents, {
    keyType,
    keyValue,
    maxWindowMs,
    ...(Array.isArray(expectedFlow) ? { expectedFlow } : {}),
  });

  const timeline = matched.timeline.map((event) => ({
    eventId: event.eventId,
    probeId: event.probeId,
    timestampEpochMs: event.timestampEpochMs,
    ...(typeof event.lineKey === "string" ? { lineKey: event.lineKey } : {}),
  }));

  return {
    status: matched.status === "ok" ? "ok" : "fail_closed",
    reasonCode: matched.reasonCode === "ok" ? "ok" : matched.reasonCode,
    ...(typeof policyRaw.correlationSessionId === "string"
      ? { correlationSessionId: policyRaw.correlationSessionId }
      : {}),
    keyType,
    keyValue,
    window: {
      ...(typeof policyRaw.startEpochMs === "number" ? { startEpochMs: policyRaw.startEpochMs } : {}),
      ...(typeof policyRaw.endEpochMs === "number" ? { endEpochMs: policyRaw.endEpochMs } : {}),
      maxWindowMs,
    },
    ...(Array.isArray(expectedFlow) ? { expectedFlow } : {}),
    timeline,
    generatedAtEpochMs: args.now.getTime(),
  };
}

type CorrelationIndexEntry = {
  runId: string;
  planName: string;
  runPath: string;
  generatedAtEpochMs: number;
  status: "ok" | "fail_closed";
  reasonCode: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  correlationSessionId?: string;
  window: {
    startEpochMs?: number;
    endEpochMs?: number;
    maxWindowMs: number;
  };
  probeIds: string[];
};

function asCorrelationVerdict(value: unknown): "ok" | "fail_closed" {
  return value === "ok" || value === "matched" ? "ok" : "fail_closed";
}

function asCorrelationReasonCode(value: unknown): string {
  return typeof value === "string" && value.trim().length > 0 ? value : "insufficient_evidence";
}

function normalizeCanonicalIndexEntry(typed: Record<string, unknown>): CorrelationIndexEntry | null {
  const runId = typeof typed.runId === "string" ? typed.runId : "";
  const planName = typeof typed.planName === "string" ? typed.planName : "";
  const runPath = typeof typed.runPath === "string" ? typed.runPath : "";
  if (!runId || !planName || !runPath) return null;
  return {
    runId,
    planName,
    runPath,
    generatedAtEpochMs: typeof typed.generatedAtEpochMs === "number" ? typed.generatedAtEpochMs : 0,
    status: asCorrelationVerdict(typed.status),
    reasonCode: asCorrelationReasonCode(typed.reasonCode),
    keyType: asCorrelationKeyType(typed.keyType),
    ...(typeof typed.keyValue === "string" ? { keyValue: typed.keyValue } : {}),
    ...(typeof typed.correlationSessionId === "string" ? { correlationSessionId: typed.correlationSessionId } : {}),
    window: isRecord(typed.window) ? normalizeWindowRecord(typed.window) : { maxWindowMs: 0 },
    probeIds: Array.isArray(typed.probeIds) ? typed.probeIds.map((v) => String(v)) : [],
  };
}

function toIndexEntryFromRunArtifact(args: {
  workspaceRootAbs: string;
  planName: string;
  runId: string;
  runDirAbs: string;
  correlation: CorrelationArtifact;
  now: Date;
}): CorrelationIndexEntry {
  return {
    runId: args.runId,
    planName: args.planName,
    runPath: path.relative(args.workspaceRootAbs, args.runDirAbs).replaceAll("\\", "/"),
    generatedAtEpochMs: args.correlation.generatedAtEpochMs ?? args.now.getTime(),
    status: args.correlation.status,
    reasonCode: args.correlation.reasonCode,
    keyType: args.correlation.keyType,
    ...(typeof args.correlation.keyValue === "string" ? { keyValue: args.correlation.keyValue } : {}),
    ...(typeof args.correlation.correlationSessionId === "string"
      ? { correlationSessionId: args.correlation.correlationSessionId }
      : {}),
    window: args.correlation.window,
    probeIds: Array.from(new Set(args.correlation.timeline.map((event) => event.probeId))).sort(),
  };
}

function correlationFileToIndexEntry(args: {
  workspaceRootAbs: string;
  runDirAbs: string;
  correlation: Record<string, unknown>;
  now: Date;
}): CorrelationIndexEntry | null {
  const relativeRun = path.relative(args.workspaceRootAbs, args.runDirAbs).replaceAll("\\", "/");
  const match = relativeRun.match(/^\.mcpjvm\/[^/]+\/plans\/regression\/([^/]+)\/runs\/([^/]+)$/);
  if (!match) return null;
  const planName = match[1];
  const runId = match[2];
  if (!planName || !runId) return null;
  return {
    runId,
    planName,
    runPath: relativeRun,
    generatedAtEpochMs:
      typeof args.correlation.generatedAtEpochMs === "number"
        ? args.correlation.generatedAtEpochMs
        : args.now.getTime(),
    status: asCorrelationVerdict(args.correlation.status),
    reasonCode: asCorrelationReasonCode(args.correlation.reasonCode),
    keyType: asCorrelationKeyType(args.correlation.keyType),
    ...(typeof args.correlation.keyValue === "string" ? { keyValue: args.correlation.keyValue } : {}),
    ...(typeof args.correlation.correlationSessionId === "string"
      ? { correlationSessionId: args.correlation.correlationSessionId }
      : {}),
    window: isRecord(args.correlation.window) ? normalizeWindowRecord(args.correlation.window) : { maxWindowMs: 0 },
    probeIds: Array.isArray(args.correlation.timeline)
      ? Array.from(
          new Set(
            args.correlation.timeline
              .filter((event) => isRecord(event) && typeof event.probeId === "string")
              .map((event) => String((event as Record<string, unknown>).probeId)),
          ),
        ).sort()
      : [],
  };
}

function normalizeWindowRecord(input: Record<string, unknown>): {
  startEpochMs?: number;
  endEpochMs?: number;
  maxWindowMs: number;
} {
  return {
    ...(typeof input.startEpochMs === "number" ? { startEpochMs: input.startEpochMs } : {}),
    ...(typeof input.endEpochMs === "number" ? { endEpochMs: input.endEpochMs } : {}),
    maxWindowMs: Number(input.maxWindowMs ?? 0),
  };
}

async function updateCorrelationIndex(args: {
  workspaceRootAbs: string;
  projectName?: string;
  correlation: CorrelationArtifact;
  runId: string;
  planName: string;
  runDirAbs: string;
  now: Date;
}): Promise<string> {
  const { projectRootAbs } = await resolveProjectRootAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
      ? { projectName: args.projectName.trim() }
      : {}),
  });
  const indexPathAbs = path.join(projectRootAbs, "correlation-index.json");
  let entries: CorrelationIndexEntry[] = [];
  try {
    const current = JSON.parse(await fs.readFile(indexPathAbs, "utf8")) as { entries?: unknown };
    if (Array.isArray(current.entries)) {
      entries = current.entries
        .filter((item) => isRecord(item))
        .map((item) => normalizeCanonicalIndexEntry(item as Record<string, unknown>))
        .filter((entry): entry is CorrelationIndexEntry => entry !== null);
    }
  } catch {
    entries = [];
  }

  const nextEntry = toIndexEntryFromRunArtifact({
    workspaceRootAbs: args.workspaceRootAbs,
    planName: args.planName,
    runId: args.runId,
    runDirAbs: args.runDirAbs,
    correlation: args.correlation,
    now: args.now,
  });

  const filtered = entries.filter((entry) => !(entry.planName === args.planName && entry.runId === args.runId));
  const withNext = filtered.concat(nextEntry);
  const existingEntries: CorrelationIndexEntry[] = [];
  for (const entry of withNext) {
    const runAbs = path.join(args.workspaceRootAbs, entry.runPath);
    try {
      const stat = await fs.stat(runAbs);
      if (stat.isDirectory()) existingEntries.push(entry);
    } catch {
      // prune stale index entry
    }
  }
  existingEntries.sort((a, b) => {
    if (a.generatedAtEpochMs !== b.generatedAtEpochMs) return a.generatedAtEpochMs - b.generatedAtEpochMs;
    return `${a.planName}:${a.runId}`.localeCompare(`${b.planName}:${b.runId}`);
  });

  await fs.mkdir(path.dirname(indexPathAbs), { recursive: true });
  await writeJsonFile(indexPathAbs, {
    version: 1,
    generatedAt: args.now.toISOString(),
    entries: existingEntries,
  });
  return indexPathAbs;
}

export async function rebuildCorrelationIndex(args: {
  workspaceRootAbs: string;
  projectName?: string;
  now?: Date;
}): Promise<CorrelationIndexRebuildResult> {
  const now = args.now ?? new Date();
  const root = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const entries: CorrelationIndexEntry[] = [];
  try {
    const plans = await fs.readdir(root, { withFileTypes: true });
    for (const planDir of plans) {
      if (!planDir.isDirectory()) continue;
      const runsRoot = path.join(root, planDir.name, "runs");
      let runDirs: import("node:fs").Dirent[] = [];
      try {
        runDirs = await fs.readdir(runsRoot, { withFileTypes: true });
      } catch {
        continue;
      }
      for (const runDir of runDirs) {
        if (!runDir.isDirectory()) continue;
        const runAbs = path.join(runsRoot, runDir.name);
        const corrPath = path.join(runAbs, "correlation", "correlation.json");
        let parsed: unknown;
        try {
          parsed = JSON.parse(await fs.readFile(corrPath, "utf8"));
        } catch {
          continue;
        }
        if (!isRecord(parsed)) continue;
        const entry = correlationFileToIndexEntry({
          workspaceRootAbs: args.workspaceRootAbs,
          runDirAbs: runAbs,
          correlation: parsed,
          now,
        });
        if (entry) entries.push(entry);
      }
    }
  } catch {
    // no regression folder yet
  }

  entries.sort((a, b) => {
    if (a.generatedAtEpochMs !== b.generatedAtEpochMs) return a.generatedAtEpochMs - b.generatedAtEpochMs;
    return `${a.planName}:${a.runId}`.localeCompare(`${b.planName}:${b.runId}`);
  });

  const { projectRootAbs } = await resolveProjectRootAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
      ? { projectName: args.projectName.trim() }
      : {}),
  });
  const indexPathAbs = path.join(projectRootAbs, "correlation-index.json");
  await fs.mkdir(path.dirname(indexPathAbs), { recursive: true });
  await writeJsonFile(indexPathAbs, {
    version: 1,
    generatedAt: now.toISOString(),
    entries,
  });
  return { indexPathAbs, entriesCount: entries.length };
}

async function writeJsonFile(filePathAbs: string, payload: Record<string, unknown>): Promise<void> {
  await fs.writeFile(filePathAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function resolveProjectRootAbs(args: {
  workspaceRootAbs: string;
  projectName?: string;
}): Promise<{ projectName: string; projectRootAbs: string }> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  return {
    projectName: path.basename(projectRootAbs),
    projectRootAbs,
  };
}

function normalizePlanName(planName: string): string {
  const normalized = planName.trim();
  if (!normalized) {
    throw new Error("plan_name_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("plan_name_invalid");
  }
  return normalized;
}

export function buildRunArtifactDirAbs(workspaceRootAbs: string, planName: string, runId: string): string {
  if (!workspaceRootAbs || workspaceRootAbs.trim() === "") {
    throw new Error("workspace_root_missing");
  }
  const safePlanName = normalizePlanName(planName);
  if (!RUN_ID_PATTERN.test(runId)) {
    throw new Error("run_id_invalid");
  }
  const mcpjvmRoot = path.join(workspaceRootAbs, ".mcpjvm");
  let projectName: string | null = null;
  try {
    const entries = readdirSync(mcpjvmRoot, { withFileTypes: true });
    const candidates = entries
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .filter((name) => {
        try {
          return statSync(path.join(mcpjvmRoot, name, "projects.json")).isFile();
        } catch {
          return false;
        }
      });
    if (candidates.length === 1) {
      projectName = candidates[0] ?? null;
    } else if (candidates.length === 0) {
      throw new Error("project_artifact_missing");
    } else {
      throw new Error("project_artifact_ambiguous");
    }
  } catch (error) {
    if (error instanceof Error && (error.message === "project_artifact_missing" || error.message === "project_artifact_ambiguous")) {
      throw error;
    }
    throw new Error("project_artifact_missing");
  }
  return path.join(workspaceRootAbs, ".mcpjvm", String(projectName), "plans", "regression", safePlanName, "runs", runId);
}

export async function writeRegressionRunArtifacts(
  args: WriteRegressionRunArtifactsInput,
): Promise<RegressionRunArtifactsWriteResult> {
  if (!args.planRef?.name) {
    throw new Error("plan_name_missing");
  }
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const runDirAbs = path.join(plansRootAbs, normalizePlanName(args.planRef.name), "runs", args.runId);
  if (!RUN_ID_PATTERN.test(args.runId)) {
    throw new Error("run_id_invalid");
  }
  await fs.mkdir(runDirAbs, { recursive: true });

  const explicitSecretPaths = new Set(args.secretContextKeys ?? []);
  const now = args.now ?? new Date();
  const resolvedSecretRedactionMeta = buildResolvedSecretRedactionMeta({
    resolvedContext: args.resolvedContext,
    explicitSecretPaths,
  });

  const contextResolvedPathAbs = path.join(runDirAbs, "context.resolved.json");
  const executionResultPathAbs = path.join(runDirAbs, "execution.result.json");
  const evidencePathAbs = path.join(runDirAbs, "evidence.json");
  const correlationDirAbs = path.join(runDirAbs, "correlation");
  const correlationPathAbs = path.join(correlationDirAbs, "correlation.json");

  const contextResolvedPayload = sanitizeSuitePersistedContext(
    {
      resolvedAt: now.toISOString(),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      ...(resolvedSecretRedactionMeta ? { redaction: resolvedSecretRedactionMeta } : {}),
      ...stripRedundantResolvedContextFields(args.resolvedContext),
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const executionResultPayload = sanitizeSuitePersistedContext(
    {
      ...normalizeExecutionResultPayload(args.executionResult),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      runId: args.runId,
      ...(typeof args.executionProfile === "string" ? { executionProfile: args.executionProfile } : {}),
      ...(typeof args.suiteRunId === "string" ? { suiteRunId: args.suiteRunId } : {}),
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  const evidencePayload = sanitizeSuitePersistedContext(
    {
      ...normalizeEvidencePayload(args.evidence),
      ...(args.planRef ? { planRef: args.planRef } : {}),
      runId: args.runId,
    },
    explicitSecretPaths,
  ) as Record<string, unknown>;

  await writeJsonFile(contextResolvedPathAbs, contextResolvedPayload);
  await writeJsonFile(executionResultPathAbs, executionResultPayload);
  await writeJsonFile(evidencePathAbs, evidencePayload);

  let writtenCorrelationPathAbs: string | undefined;
  let writtenCorrelationIndexPathAbs: string | undefined;
  const correlation = args.correlation
    ? args.correlation
    : toCorrelationArtifactFromEvidence({
        evidence: args.evidence,
        resolvedContext: args.resolvedContext,
        now,
      });
  if (correlation) {
    await fs.mkdir(correlationDirAbs, { recursive: true });
    const correlationPayload = sanitizeSuitePersistedContext(
      normalizeCorrelationPayload(correlation),
      explicitSecretPaths,
    ) as Record<string, unknown>;
    await writeJsonFile(correlationPathAbs, correlationPayload);
    writtenCorrelationPathAbs = correlationPathAbs;
    writtenCorrelationIndexPathAbs = await updateCorrelationIndex({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      correlation,
      runId: args.runId,
      planName: args.planRef.name,
      runDirAbs,
      now,
    });
  }

  return {
    runDirAbs,
    contextResolvedPathAbs,
    executionResultPathAbs,
    evidencePathAbs,
    ...(writtenCorrelationPathAbs ? { correlationPathAbs: writtenCorrelationPathAbs } : {}),
    ...(writtenCorrelationIndexPathAbs ? { correlationIndexPathAbs: writtenCorrelationIndexPathAbs } : {}),
  };
}
