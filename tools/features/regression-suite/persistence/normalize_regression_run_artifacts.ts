/**
 * Regression run normalization owner.
 *
 * Canonicalizes execution, watcher, external-verification, and correlation
 * evidence before the persistence coordinator writes the run Artifacts.
 */
import type {
  CorrelationArtifact,
  RegressionExecutionContinuation,
  RegressionExternalVerificationPhaseStatus,
  RegressionRunExecutionResult,
  RegressionRunWatcherOutcome,
  RegressionRunWatcherResult,
  RegressionRunWatcherResultStatus,
  RegressionWatcherReasonCode,
  RegressionRunWatcherWaitSummary,
  WatcherExecutionEvidence,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import type { NormalizedExternalVerificationResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import { validateNormalizedExternalVerificationResultShape } from "../../../spec/regression-execution-plan-spec/src/external_verification_contract.util";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function stripRedundantResolvedContextFields(
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

export function normalizeEvidencePayload(
  evidence: Record<string, unknown>,
): Record<string, unknown> {
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
  if ("externalVerificationExecutions" in evidence) {
    const externalVerificationExecutions = normalizeExternalVerificationResults(
      evidence.externalVerificationExecutions,
      "external_verification_evidence_invalid",
    );
    if (externalVerificationExecutions.length === 0) {
      delete normalized.externalVerificationExecutions;
    } else {
      normalized.externalVerificationExecutions = externalVerificationExecutions;
    }
  }
  return normalized;
}

export function normalizeExecutionResultPayload(
  executionResult: RegressionRunExecutionResult,
): RegressionRunExecutionResult {
  if (
    typeof executionResult.watchers === "undefined" &&
    typeof executionResult.externalVerification === "undefined" &&
    typeof executionResult.externalVerificationStatus === "undefined"
  ) {
    return executionResult;
  }
  if (typeof executionResult.watchers !== "undefined" && !Array.isArray(executionResult.watchers)) {
    throw new Error("watcher_execution_result_invalid");
  }
  return {
    ...executionResult,
    ...(typeof executionResult.continuation === "undefined"
      ? {}
      : {
          continuation: normalizeExecutionContinuation(executionResult.continuation),
        }),
    ...(typeof executionResult.externalVerificationStatus === "undefined"
      ? {}
      : {
          externalVerificationStatus: normalizeExternalVerificationPhaseStatus(
            executionResult.externalVerificationStatus,
            "external_verification_execution_result_invalid",
          ),
        }),
    ...(typeof executionResult.watchers === "undefined"
      ? {}
      : {
          watchers: executionResult.watchers
            .map((watcher) => normalizeExecutionWatcherResult(watcher))
            .map((watcher) => {
              if (!watcher) {
                throw new Error("watcher_execution_result_invalid");
              }
              return watcher;
            }),
        }),
    ...(typeof executionResult.externalVerification === "undefined"
      ? {}
      : {
          externalVerification: normalizeExternalVerificationResults(
            executionResult.externalVerification,
            "external_verification_execution_result_invalid",
          ),
        }),
  };
}

function normalizeExternalVerificationPhaseStatus(
  value: unknown,
  invalidErrorCode: "external_verification_execution_result_invalid",
): RegressionExternalVerificationPhaseStatus {
  if (
    value === "not_configured" ||
    value === "pass" ||
    value === "fail" ||
    value === "blocked" ||
    value === "in_progress" ||
    value === "skipped_dependency"
  ) {
    return value;
  }
  throw new Error(invalidErrorCode);
}

function normalizeExecutionContinuation(
  value: RegressionExecutionContinuation,
): RegressionExecutionContinuation {
  if (
    value.phase === "watchers" &&
    Number.isInteger(value.watcherIndex) &&
    value.watcherIndex >= 0 &&
    typeof value.phaseStartedAt === "string" &&
    value.phaseStartedAt.trim().length > 0
  ) {
    return {
      phase: "watchers",
      watcherIndex: value.watcherIndex,
      phaseStartedAt: value.phaseStartedAt,
    };
  }
  if (
    value.phase === "external_verification" &&
    Number.isInteger(value.verificationIndex) &&
    value.verificationIndex >= 0 &&
    typeof value.phaseStartedAt === "string" &&
    value.phaseStartedAt.trim().length > 0
  ) {
    return {
      phase: "external_verification",
      verificationIndex: value.verificationIndex,
      phaseStartedAt: value.phaseStartedAt,
    };
  }
  throw new Error("execution_continuation_invalid");
}

function normalizeExternalVerificationResults(
  results: unknown,
  invalidErrorCode:
    "external_verification_evidence_invalid" | "external_verification_execution_result_invalid",
) {
  if (typeof results === "undefined") {
    return [];
  }
  if (!Array.isArray(results)) {
    throw new Error(invalidErrorCode);
  }
  return results.map((entry) => {
    const validation = validateNormalizedExternalVerificationResultShape(entry);
    if (!validation.ok) {
      throw new Error(invalidErrorCode);
    }
    return compactExternalVerificationResult(entry as NormalizedExternalVerificationResult);
  });
}

function compactExternalVerificationResult(
  entry: NormalizedExternalVerificationResult,
): NormalizedExternalVerificationResult {
  const compacted: NormalizedExternalVerificationResult = { ...entry };
  if (isRecord(entry.response)) {
    const response = entry.response;
    const body = typeof response.body === "string" ? response.body : "";
    const headerNames = isRecord(response.headers) ? Object.keys(response.headers).sort() : [];
    compacted.response = {
      ...(typeof response.statusCode === "number" ? { statusCode: response.statusCode } : {}),
      ...(typeof response.durationMs === "number" ? { durationMs: response.durationMs } : {}),
      bodyFormat: typeof response.bodyJson === "undefined" ? "text" : "json",
      bodyBytes: Buffer.byteLength(body, "utf8"),
      hasBodyJson: typeof response.bodyJson !== "undefined",
      ...(headerNames.length > 0 ? { headerNames } : {}),
    };
  }
  if (Array.isArray(entry.extractResults)) {
    compacted.extractResults = entry.extractResults.map((extractResult) => ({
      from: extractResult.from,
      as: extractResult.as,
      required: extractResult.required,
      status: extractResult.status,
      ...(typeof extractResult.reasonCode === "string"
        ? { reasonCode: extractResult.reasonCode }
        : {}),
    }));
  }
  if (Array.isArray(entry.assertions)) {
    compacted.assertions = entry.assertions.map((assertion) => ({
      id: assertion.id,
      actualPath: assertion.actualPath,
      operator: assertion.operator,
      status: assertion.status,
      ...(typeof assertion.expected === "undefined" ? {} : { expected: assertion.expected }),
      ...(typeof assertion.message === "undefined" ? {} : { message: assertion.message }),
      ...(typeof assertion.reasonCode === "undefined" ? {} : { reasonCode: assertion.reasonCode }),
    }));
  }
  if (typeof compacted.extractedContext !== "undefined") {
    delete compacted.extractedContext;
  }
  return compacted;
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
    typeof watcher.providerType === "string" && watcher.providerType.trim().length > 0
      ? watcher.providerType
      : null;
  const status = normalizeExecutionWatcherStatus(watcher.status);
  const reasonCode = normalizeWatcherReasonCode(watcher.reasonCode);
  const outcome = normalizeExecutionWatcherOutcome({
    outcome: watcher.outcome,
    reasonCode,
  });
  const attemptCount =
    typeof watcher.attemptCount === "number" && Number.isFinite(watcher.attemptCount)
      ? watcher.attemptCount
      : null;
  const durationMs =
    typeof watcher.durationMs === "number" && Number.isFinite(watcher.durationMs)
      ? watcher.durationMs
      : null;
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

function normalizeWatcherExecutionEvidence(watcherExecutions: unknown): WatcherExecutionEvidence[] {
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

function normalizeWatcherExecutionEvidenceEntry(entry: unknown): WatcherExecutionEvidence | null {
  if (!isRecord(entry)) {
    return null;
  }
  const id = typeof entry.id === "string" && entry.id.trim().length > 0 ? entry.id : null;
  const dependencyStepOrder =
    typeof entry.dependencyStepOrder === "number" && Number.isFinite(entry.dependencyStepOrder)
      ? entry.dependencyStepOrder
      : null;
  const providerType =
    typeof entry.providerType === "string" && entry.providerType.trim().length > 0
      ? entry.providerType
      : null;
  const attemptCount =
    typeof entry.attemptCount === "number" && Number.isFinite(entry.attemptCount)
      ? entry.attemptCount
      : null;
  const durationMs =
    typeof entry.durationMs === "number" && Number.isFinite(entry.durationMs)
      ? entry.durationMs
      : null;
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
  if (value === "watcher_actual_path_missing_retry_exhausted") return "watcher_actual_path_missing_retry_exhausted";
  if (value === "optional_actual_path_missing") return "optional_actual_path_missing";
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
    ...(typeof value.timeoutMs === "number" && Number.isFinite(value.timeoutMs)
      ? { timeoutMs: value.timeoutMs }
      : {}),
    timeoutSource:
      value.timeoutSource === "watcher_override" ||
      value.timeoutSource === "project_default" ||
      value.timeoutSource === "unresolved"
        ? value.timeoutSource
        : "unresolved",
    ...(typeof value.retryMax === "number" && Number.isFinite(value.retryMax)
      ? { retryMax: value.retryMax }
      : {}),
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

function normalizeExecutionWatcherStatus(
  value: unknown,
): RegressionRunWatcherResultStatus | undefined {
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
  if (args.reasonCode === "watcher_actual_path_missing_retry_exhausted") return "blocked";
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
  if (
    args.outcome === "timed_out" ||
    args.outcome === "timeout" ||
    args.reasonCode === "watcher_timeout"
  ) {
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
    if (args.reasonCode === "watcher_actual_path_missing_retry_exhausted") return "expectation_failed";
    return "configuration_invalid";
  }
  if (args.reasonCode === "watcher_verified") return "verified";
  if (args.reasonCode === "watcher_timeout") return "timeout";
  if (args.reasonCode === "watcher_target_unreachable") return "target_unreachable";
  if (args.reasonCode === "watcher_expectation_failed") return "expectation_failed";
  if (args.reasonCode === "watcher_actual_path_missing_retry_exhausted") return "expectation_failed";
  if (args.reasonCode === "watcher_configuration_invalid") return "configuration_invalid";
  if (args.reasonCode === "watcher_dependency_invalid") return "dependency_invalid";
  return undefined;
}

export function normalizeCorrelationPayload(
  correlation: CorrelationArtifact,
): Record<string, unknown> {
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
    ...(Array.isArray(correlation.strictLineExpectations)
      ? { strictLineExpectations: correlation.strictLineExpectations }
      : {}),
    timeline,
    ...(Array.isArray(correlation.evidenceRefs) ? { evidenceRefs: correlation.evidenceRefs } : {}),
    ...(typeof correlation.generatedAtEpochMs === "number"
      ? { generatedAtEpochMs: correlation.generatedAtEpochMs }
      : {}),
  };
}
