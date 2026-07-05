import type { PreflightResult } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

export type RegressionRunStatus = "pass" | "fail" | "blocked";
export type RegressionWatcherPhaseStatus = "not_configured" | "pass" | "fail" | "blocked";

export type RegressionPlanReference = {
  name?: string;
  path?: string;
};

export type RegressionRunExecutionResult = {
  status: RegressionRunStatus;
  triggerStatus?: RegressionRunStatus;
  watcherStatus?: RegressionWatcherPhaseStatus;
  preflight: PreflightResult;
  startedAt: string | null;
  endedAt: string | null;
  steps: RegressionRunStepResult[];
  watchers?: RegressionRunWatcherResult[];
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

export type RegressionRunAssertionStatus = "pass" | "fail" | "blocked_invalid";

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
  lastObservation?: Record<string, unknown>;
  assertions?: RegressionRunAssertionResult[];
  attempts?: RegressionRunWatcherAttempt[];
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
    watcherExecutions?: Array<Record<string, unknown>>;
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
};

export type CorrelationIndexRebuildResult = {
  indexPathAbs: string;
  entriesCount: number;
};

export type CorrelationReasonCode =
  | "ok"
  | "missing_correlation_key"
  | "correlation_key_extraction_failed"
  | "missing_correlation_session_id"
  | "no_matching_events"
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
  timeline: CorrelationTimelineEvent[];
  evidenceRefs?: string[];
  generatedAtEpochMs?: number;
};

