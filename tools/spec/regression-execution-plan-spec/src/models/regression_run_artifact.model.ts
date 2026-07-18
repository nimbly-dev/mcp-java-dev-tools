import type {
  NormalizedExternalVerificationResult,
  PreflightResult,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

export type RegressionRunStatus = "pass" | "fail" | "blocked" | "in_progress";
export type RegressionWatcherPhaseStatus =
  | "not_configured"
  | "pass"
  | "fail"
  | "blocked"
  | "in_progress";
export type RegressionExternalVerificationPhaseStatus =
  | "not_configured"
  | "pass"
  | "fail"
  | "blocked"
  | "in_progress"
  | "skipped_dependency";

export type RegressionExecutionContinuation =
  | {
      phase: "watchers";
      watcherIndex: number;
      phaseStartedAt: string;
      watcherName?: string;
      dependencyStepOrder?: number;
      providerType?: string;
      deadlineAtEpochMs?: number;
      timeoutMs?: number;
      pollIntervalMs?: number;
      retryMax?: number;
      attemptCount?: number;
      nextAttemptAt?: string;
      lastObservation?: Record<string, unknown>;
      lastAssertion?: Record<string, unknown>;
      attempts?: RegressionRunWatcherAttempt[];
    }
  | {
      phase: "external_verification";
      verificationIndex: number;
      phaseStartedAt: string;
    };

export type RegressionPlanReference = {
  name?: string;
  path?: string;
};

export type RegressionRunExecutionResult = {
  status: RegressionRunStatus;
  triggerStatus?: RegressionRunStatus;
  watcherStatus?: RegressionWatcherPhaseStatus;
  externalVerificationStatus?: RegressionExternalVerificationPhaseStatus;
  continuation?: RegressionExecutionContinuation;
  preflight: PreflightResult;
  startedAt: string | null;
  endedAt: string | null;
  steps: RegressionRunStepResult[];
  watchers?: RegressionRunWatcherResult[];
  externalVerification?: NormalizedExternalVerificationResult[];
};

export type RegressionRunStepResultStatus =
  | "pass"
  | "fail_assertion"
  | "fail_http"
  | "blocked_dependency"
  | "blocked_runtime"
  | "skipped_condition_false";

export type RegressionConditionEvaluationStatus = true | false | "blocked_invalid";

export type RegressionConditionEvaluation = {
  status: RegressionConditionEvaluationStatus;
  reasonCode?:
    | "step_condition_malformed"
    | "step_condition_operator_invalid"
    | "step_condition_forward_reference"
    | "step_condition_path_missing"
    | "step_condition_type_mismatch";
};

export type RegressionRunStepExtractStatus = "resolved" | "unresolved";

export type RegressionRunStepExtractResult = {
  from: string;
  as: string;
  required: boolean;
  status: RegressionRunStepExtractStatus;
  reasonCode: "ok" | "extract_path_missing";
};

export type RegressionRunStepExtractApplyResult = {
  context: Record<string, unknown>;
  outcomes: RegressionRunStepExtractResult[];
  hasRequiredUnresolved: boolean;
};

export type RegressionRunAssertionStatus = "pass" | "fail" | "blocked_invalid" | "skipped_optional";

export type RegressionRunAssertionResult = {
  id: string;
  operator: string;
  actualPath: string;
  required: boolean;
  status: RegressionRunAssertionStatus;
  reasonCode: string;
  actual?: unknown;
  expected?: unknown;
  message?: string;
};

export type RegressionRunWatcherResultStatus =
  | "pass"
  | "fail_assertion"
  | "blocked_dependency"
  | "blocked_runtime";

export type RegressionRunWatcherOutcome =
  | "verified"
  | "failed_expectation"
  | "timed_out"
  | "blocked";

export type RegressionWatcherReasonCode =
  | "watcher_verified"
  | "watcher_timeout"
  | "watcher_target_unreachable"
  | "watcher_expectation_failed"
  | "watcher_actual_path_missing_retry_exhausted"
  | "optional_actual_path_missing"
  | "watcher_configuration_invalid"
  | "watcher_dependency_invalid";

export type RegressionRunWatcherAttemptStatus =
  | "pass"
  | "fail_assertion"
  | "fail_http"
  | "blocked_runtime"
  | "blocked_invalid";

export type RegressionRunWatcherAttempt = {
  attempt: number;
  status: RegressionRunWatcherAttemptStatus;
  durationMs: number;
  statusCode?: number;
  reasonCode?: string;
  observedAt: string;
};

export type RegressionRunWatcherWaitSummary = {
  timeoutMs?: number;
  timeoutSource: "watcher_override" | "project_default" | "unresolved";
  retryMax?: number;
  retrySource: "watcher_override" | "project_default" | "unresolved";
  pollIntervalMs?: number;
};

export type RegressionRunWatcherResult = Record<string, unknown> & {
  id: string;
  dependencyStepOrder: number;
  providerType: string;
  status: RegressionRunWatcherResultStatus;
  outcome: RegressionRunWatcherOutcome;
  attemptCount: number;
  durationMs: number;
  waitPolicy: RegressionRunWatcherWaitSummary;
  startedAtEpochMs?: number;
  deadlineAtEpochMs?: number;
  reasonCode?: RegressionWatcherReasonCode;
  lastObservation?: Record<string, unknown>;
  assertions?: RegressionRunAssertionResult[];
  attempts?: RegressionRunWatcherAttempt[];
};

export type WatcherExecutionEvidenceStatus = "ok" | "fail_closed" | "timed_out";

export type WatcherExecutionEvidenceOutcome =
  | "verified"
  | "timeout"
  | "target_unreachable"
  | "expectation_failed"
  | "configuration_invalid"
  | "dependency_invalid";

export type WatcherExecutionEvidence = {
  id: string;
  dependencyStepOrder: number;
  providerType: string;
  status: WatcherExecutionEvidenceStatus;
  outcome: WatcherExecutionEvidenceOutcome;
  attemptCount: number;
  durationMs: number;
  reasonCode: RegressionWatcherReasonCode;
  waitPolicy: RegressionRunWatcherWaitSummary;
  lastObservation?: Record<string, unknown>;
  attempts?: RegressionRunWatcherAttempt[];
  assertions?: RegressionRunAssertionResult[];
  reasonMeta?: Record<string, unknown>;
};

export type RegressionRunStepResult = Record<string, unknown> & {
  order: number;
  id: string;
  status: RegressionRunStepResultStatus;
  extract?: RegressionRunStepExtractResult[];
  assertions?: RegressionRunAssertionResult[];
  conditionEvaluation?: RegressionConditionEvaluation;
};

export type DiscoveryEvidenceOutcome = {
  key: string;
  source: "datasource" | "runtime_context";
  outcome:
    | "resolved"
    | "unresolved_empty"
    | "unresolved_ambiguous"
    | "blocked_policy"
    | "blocked_runtime_error"
    | "blocked_source_unsupported"
    | "blocked_timeout"
    | "blocked_mutation";
  reasonCode:
    | "ok"
    | "discoverable_prerequisite_policy_disabled"
    | "discovery_empty_result"
    | "discovery_ambiguous_result"
    | "discovery_adapter_failure"
    | "discovery_source_unsupported"
    | "discovery_timeout"
    | "discovery_mutation_blocked";
  candidateCount?: number;
  sourceRef?: string;
};

export type DiscoveryEvidence = {
  attempted: boolean;
  status: "resolved" | "blocked";
  reasonCode:
    | "ok"
    | "discoverable_prerequisite_policy_disabled"
    | "discovery_empty_result"
    | "discovery_ambiguous_result"
    | "discovery_adapter_failure"
    | "discovery_source_unsupported"
    | "discovery_timeout"
    | "discovery_mutation_blocked";
  outcomes: DiscoveryEvidenceOutcome[];
};

export type WriteRegressionRunArtifactsInput = {
  workspaceRootAbs: string;
  projectName?: string;
  runId: string;
  executionProfile?: string;
  suiteRunId?: string;
  planRef?: RegressionPlanReference;
  resolvedContext: Record<string, unknown>;
  secretContextKeys?: string[];
  executionResult: RegressionRunExecutionResult;
  evidence: {
    targetResolution: Array<Record<string, unknown>>;
    discovery?: DiscoveryEvidence;
    watcherExecutions?: WatcherExecutionEvidence[];
    externalVerificationExecutions?: NormalizedExternalVerificationResult[];
    [key: string]: unknown;
  };
  correlation?: CorrelationArtifact;
  now?: Date;
};

export type RegressionRunArtifactsWriteResult = {
  runDirAbs: string;
  contextResolvedPathAbs: string;
  executionResultPathAbs: string;
  evidencePathAbs: string;
  correlationPathAbs?: string;
  correlationIndexPathAbs?: string;
  persistenceWarnings?: Array<{
    reasonCode: string;
    reason: string;
    nextAction: string;
  }>;
};

export type CorrelationIndexRebuildResult = {
  ok: false;
  reasonCode: "legacy_write_disabled";
  reason: string;
  nextAction: "use_sqlite_state_store";
};

export type CorrelationReasonCode =
  | "ok"
  | "missing_correlation_key"
  | "correlation_key_extraction_failed"
  | "missing_correlation_session_id"
  | "no_matching_events"
  | "correlation_probe_scope_mismatch"
  | "correlation_key_not_observed"
  | "correlation_runtime_identity_missing"
  | "correlation_probe_response_invalid"
  | "correlation_context_not_propagated"
  | "no_runs_in_scope"
  | "window_exceeded"
  | "ambiguous_correlation"
  | "ambiguous_cross_plan_correlation"
  | "flow_expectation_mismatch"
  | "insufficient_evidence";

export type CorrelationVerdict = "ok" | "fail_closed";

export type CorrelationTimelineEvent = {
  eventId: string;
  probeId: string;
  timestampEpochMs: number;
  lineKey?: string;
  eventType?: string;
  evidenceRef?: string;
  sequenceOrder?: number;
  selectorPolicy?: "exact_instance" | "any_instance" | "all_instances" | "aggregate" | "quorum";
  operator?: "exact" | "at_least" | "at_most" | "range";
  expectedHitDelta?: number;
  expectedMinHitDelta?: number;
  expectedMaxHitDelta?: number;
  runtimeInstanceId?: string;
  keyFingerprint?: string;
  baselineHitCount?: number;
  currentHitCount?: number;
  stepOrder?: number;
};

export type CorrelationArtifact = {
  status: CorrelationVerdict;
  reasonCode: CorrelationReasonCode;
  reasonMeta?: Record<string, unknown>;
  correlationSessionId?: string;
  keyType: "traceId" | "requestId" | "messageId";
  keyValue?: string;
  window: {
    startEpochMs?: number;
    endEpochMs?: number;
    maxWindowMs: number;
  };
  expectedFlow?: string[];
  strictLineExpectations?: Array<{
    strictLineKey: string;
    sequenceOrder: number;
    stepOrder?: number;
    selectorPolicy: "exact_instance" | "any_instance" | "all_instances" | "aggregate" | "quorum";
    operator: "exact" | "at_least" | "at_most" | "range";
    expectedHitDelta?: number;
    expectedMinHitDelta?: number;
    expectedMaxHitDelta?: number;
    label?: string;
  }>;
  timeline: CorrelationTimelineEvent[];
  evidenceRefs?: string[];
  generatedAtEpochMs?: number;
};
