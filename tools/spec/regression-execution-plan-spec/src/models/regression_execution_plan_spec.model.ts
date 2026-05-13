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
  | "target_ambiguous"
  | "strict_probe_key_invalid"
  | "invalid_discoverable_prerequisite"
  | "secret_default_forbidden"
  | "step_expectations_missing"
  | "step_expectation_invalid"
  | "top_level_expectations_unsupported"
  | "correlation_session_missing"
  | "correlation_window_invalid"
  | "correlation_key_invalid"
  | "project_artifact_missing"
  | "project_artifact_invalid"
  | "workspace_root_invalid"
  | "env_key_missing"
  | "runtime_context_unknown"
  | "external_system_invalid"
  | "external_healthcheck_failed";

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
  };
};

export type PlanStep = {
  order: number;
  id: string;
  targetRef: number;
  protocol: string;
  transport: Record<string, unknown>;
  extract?: Array<{ from: string; as: string }>;
  expect: PlanStepExpectation[];
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

export type PlanContract = {
  targets: PlanTarget[];
  prerequisites: PlanPrerequisite[];
  steps: PlanStep[];
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
      | "workspace_root_invalid"
      | "env_key_missing"
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

