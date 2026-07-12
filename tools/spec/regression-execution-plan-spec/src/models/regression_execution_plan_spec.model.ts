export type RegressionExecutionIntent = "regression";

export type PreflightStatus =
  | "ready"
  | "needs_user_input"
  | "needs_discovery"
  | "stale_plan"
  | "blocked_ambiguous"
  | "blocked_invalid";

export type PreflightReasonCode =
  | "ok"
  | "missing_prerequisites_user_input"
  | "missing_prerequisites_discoverable"
  | "missing_prerequisites_mixed"
  | "discoverable_prerequisite_policy_disabled"
  | "discovery_empty_result"
  | "discovery_ambiguous_result"
  | "discovery_adapter_failure"
  | "discovery_source_unsupported"
  | "discovery_timeout"
  | "discovery_mutation_blocked"
  | "invalid_execution_intent"
  | "target_missing"
  | "steps_missing"
  | "step_order_duplicate"
  | "step_order_non_sequential"
  | "transport_protocol_mismatch"
  | "transport_placeholder_syntax_invalid"
  | "plan_context_key_noncanonical"
  | "target_ambiguous"
  | "strict_probe_key_invalid"
  | "invalid_discoverable_prerequisite"
  | "secret_default_forbidden"
  | "step_expectations_missing"
  | "step_expectation_invalid"
  | "step_extract_invalid"
  | "top_level_expectations_unsupported"
  | "correlation_session_missing"
  | "correlation_window_invalid"
  | "correlation_key_invalid"
  | "correlation_expectation_invalid"
  | "watcher_id_invalid"
  | "watcher_dependency_invalid"
  | "watcher_provider_invalid"
  | "watcher_wait_policy_invalid"
  | "watcher_expectations_missing"
  | "watcher_expectation_invalid"
  | "external_verification_id_invalid"
  | "external_verification_provider_invalid"
  | "external_verification_request_invalid"
  | "external_verification_extract_invalid"
  | "external_verification_expectations_missing"
  | "external_verification_expectation_invalid"
  | "external_verification_placeholder_syntax_invalid"
  | "project_artifact_missing"
  | "project_artifact_invalid"
  | "project_reference_invalid"
  | "workspace_root_invalid"
  | "env_key_missing"
  | "script_execution_failed"
  | "runtime_context_unknown"
  | "external_system_invalid"
  | "external_healthcheck_failed"
  | "step_condition_malformed"
  | "step_condition_operator_invalid"
  | "step_condition_forward_reference"
  | "step_condition_path_missing"
  | "step_condition_type_mismatch";

export type PrerequisiteProvisioning = "user_input" | "discoverable";

export type PrerequisiteResolutionStatus =
  | "provided"
  | "default_applied"
  | "discoverable_pending"
  | "needs_user_input";

export type PrerequisiteResolution = {
  key: string;
  required: boolean;
  secret: boolean;
  provisioning: PrerequisiteProvisioning;
  status: PrerequisiteResolutionStatus;
};

export type PreflightResult = {
  status: PreflightStatus;
  reasonCode: PreflightReasonCode;
  missing: string[];
  discoverablePending: string[];
  checks?: string[];
  nextAction?: string;
  prerequisiteResolution: PrerequisiteResolution[];
  requiredUserAction: string[];
};

export type PlanMetadata = {
  specVersion: string;
  execution: {
    intent: RegressionExecutionIntent;
    probeVerification: boolean;
    pinStrictProbeKey: boolean;
    discoveryPolicy: "disabled" | "allow_discoverable_prerequisites";
    retry?: {
      enabled: boolean;
      maxAttempts: number;
    };
  };
};

export type PlanPrerequisite = {
  key: string;
  required: boolean;
  secret: boolean;
  provisioning: PrerequisiteProvisioning;
  discoverySource?: "datasource" | "runtime_context";
  default?: unknown;
};

export type PlanTarget = {
  type: "class_method" | "class_scope" | "module_scope";
  selectors: {
    fqcn: string;
    method?: string;
    signature?: string;
    sourceRoot?: string;
  };
  runtimeVerification?: {
    strictProbeKey: string;
    probeId?: string;
    waitForHit?: {
      timeoutMs?: number;
      pollIntervalMs?: number;
      maxRetries?: number;
    };
  };
};

export type PlanStep = {
  order: number;
  id: string;
  targetRef: number;
  protocol: string;
  transport: Record<string, unknown>;
  extract?: PlanStepExtract[];
  when?: PlanStepCondition;
  expect: PlanStepExpectation[];
};

export type PlanStepExtract = {
  from: string;
  as: string;
  required?: boolean;
};

export type StepExtractValidationResult =
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "step_extract_invalid";
      requiredUserAction: string[];
    };

export type PlanStepConditionPredicateOperator = "equals" | "not_equals" | "in" | "exists";

export type PlanStepConditionPredicate = {
  left: string;
  op: PlanStepConditionPredicateOperator;
  right?: unknown;
};

export type PlanStepCondition =
  | PlanStepConditionPredicate
  | {
      all: PlanStepCondition[];
    }
  | {
      any: PlanStepCondition[];
    }
  | {
      not: PlanStepCondition;
    };

export type PlanStepExpectationOperator =
  | "field_equals"
  | "field_exists"
  | "field_matches_regex"
  | "numeric_gte"
  | "numeric_lte"
  | "contains"
  | "probe_line_hit"
  | "outcome_status";

export type PlanStepExpectation = {
  id: string;
  actualPath: string;
  operator: PlanStepExpectationOperator;
  expected?: unknown;
  required?: boolean;
  message?: string;
};

export type PlanWatcherDependency = {
  stepOrder: number;
};

export type PlanWatcherWaitPolicy = {
  timeoutMs?: number;
  retryMax?: number;
};

export type PlanWatcherProvider = {
  type: string;
  transport?: Record<string, unknown>;
  config?: Record<string, unknown>;
};

export type PlanWatcher = {
  id: string;
  dependency: PlanWatcherDependency;
  provider: PlanWatcherProvider;
  waitPolicy?: PlanWatcherWaitPolicy;
  expect: PlanStepExpectation[];
};

export type PlanExternalVerificationProviderType = "http" | "sql";

export type PlanExternalVerificationProvider = {
  type: PlanExternalVerificationProviderType;
};

export type PlanExternalVerificationHttpRequest = {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS";
  pathTemplate?: string;
  url?: string;
  headers?: Record<string, string>;
  body?: unknown;
  timeoutMs?: number | null;
};

export type PlanExternalVerificationSqlParameter = {
  name: string;
  value?: unknown;
  valueFromContext?: string;
};

export type PlanExternalVerificationSqlRequest = {
  connectionRef: string;
  statement: string;
  parameters?: PlanExternalVerificationSqlParameter[];
  timeoutMs?: number | null;
};

export type PlanExternalVerificationRequest = {
  http?: PlanExternalVerificationHttpRequest;
  sql?: PlanExternalVerificationSqlRequest;
};

export type PlanExternalVerification = {
  id: string;
  provider: PlanExternalVerificationProvider;
  request: PlanExternalVerificationRequest;
  extract?: PlanStepExtract[];
  expect: PlanStepExpectation[];
};

export type ExternalVerificationExtractResult = {
  from: string;
  as: string;
  required: boolean;
  status: "resolved" | "unresolved";
  value?: unknown;
  reasonCode?: "extract_path_missing";
};

export type ExternalVerificationAssertionResult = {
  id: string;
  actualPath: string;
  operator: PlanStepExpectationOperator;
  status: "pass" | "fail" | "blocked";
  expected?: unknown;
  actual?: unknown;
  message?: string;
  reasonCode?: string;
};

export type ExternalVerificationHttpResponse = {
  statusCode?: number;
  body?: string;
  bodyJson?: unknown;
  headers?: Record<string, unknown>;
  durationMs?: number;
  bodyFormat?: "text" | "json";
  bodyBytes?: number;
  hasBodyJson?: boolean;
  headerNames?: string[];
};

export type ExternalVerificationSqlResult = {
  rowCount: number;
  rows: Record<string, unknown>[];
  firstRow?: Record<string, unknown>;
  durationMs?: number;
};

export type NormalizedExternalVerificationResult = {
  id: string;
  providerType: PlanExternalVerificationProviderType;
  connectionRef?: string;
  requestSummary?: Record<string, unknown>;
  status: "pass" | "fail_assertion" | "blocked_runtime";
  response?: ExternalVerificationHttpResponse;
  sql?: ExternalVerificationSqlResult;
  extractedContext?: Record<string, unknown>;
  extractResults?: ExternalVerificationExtractResult[];
  assertions?: ExternalVerificationAssertionResult[];
  reasonCode?: string;
  reasonMeta?: Record<string, unknown>;
};

export type PlanContract = {
  targets: PlanTarget[];
  prerequisites: PlanPrerequisite[];
  steps: PlanStep[];
  watchers?: PlanWatcher[];
  externalVerification?: PlanExternalVerification[];
  correlation?: PlanCorrelationPolicy;
};

export type BuildPreflightArgs = {
  metadata: PlanMetadata;
  contract: PlanContract;
  providedContext: Record<string, unknown>;
  targetCandidateCount: number;
  projectContext?: {
    status: "ok" | "blocked";
    reasonCode?:
      | "project_artifact_missing"
      | "project_artifact_invalid"
      | "project_reference_invalid"
      | "workspace_root_invalid"
      | "env_key_missing"
      | "script_execution_failed"
      | "runtime_context_unknown"
      | "external_system_invalid"
      | "external_healthcheck_failed";
    requiredUserAction?: string[];
    missing?: string[];
    checks?: string[];
    nextAction?: string;
  };
};

export type CorrelationKeyType = "traceId" | "requestId" | "messageId";
export type CorrelationSourceType = "header" | "json_path" | "capture_field";
export type CorrelationSelectorPolicy =
  | "exact_instance"
  | "any_instance"
  | "all_instances"
  | "aggregate"
  | "quorum";
export type CorrelationCountOperator = "exact" | "at_least" | "at_most" | "range";

export type PlanCorrelationLineExpectation = {
  strictLineKey: string;
  sequenceOrder: number;
  /** Required when the same Strict Line Key appears in more than one expected stage. */
  stepOrder?: number;
  selectorPolicy: CorrelationSelectorPolicy;
  operator: CorrelationCountOperator;
  expectedHitDelta?: number;
  expectedMinHitDelta?: number;
  expectedMaxHitDelta?: number;
  label?: string;
  probeIds?: string[];
};

export type PlanCorrelationPolicy = {
  enabled: boolean;
  crossPlan?: boolean;
  correlationSessionId?: string;
  key: {
    type: CorrelationKeyType;
    value?: string;
    source?: {
      type: CorrelationSourceType;
      path: string;
    };
  };
  window: {
    startEpochMs?: number;
    endEpochMs?: number;
    maxWindowMs: number;
  };
  probeIds: string[];
  expectedFlow?: string[];
  strictLineExpectations?: PlanCorrelationLineExpectation[];
  matchPolicy: {
    requireExactKeyMatch: boolean;
    requireWindowMatch: boolean;
    ambiguityStrategy: "fail_closed";
  };
  evidencePolicy?: {
    includeHeaders?: boolean;
    includePayloadPreview?: boolean;
    payloadPreviewMaxBytes?: number;
    includeExecutionPath?: boolean;
  };
};
