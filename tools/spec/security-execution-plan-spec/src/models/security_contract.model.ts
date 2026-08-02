export type SecurityMode = "blackbox" | "sidecar_assisted";

export type SecurityOutcome = "passed" | "confirmed" | "not_applicable" | "blocked";

export type SecurityProofClassification =
  | "external"
  | "internal"
  | "corroborated_external";

export type SecuritySeverity = "critical" | "high" | "medium" | "low" | "info";

export type SecurityEntrypointType =
  | "http"
  | "grpc"
  | "message"
  | "file"
  | "scheduled"
  | "internal_runtime";

export type SecurityEntrypoint = {
  id: string;
  type: SecurityEntrypointType;
  method?: string;
  path?: string;
  details?: Record<string, unknown>;
};

export type SecurityTargetBoundary = {
  environment: "local-ci";
  baseUrl?: string;
  allowedHosts: string[];
  allowedPorts: number[];
  externalNetworkAccess: "forbidden";
};

export type SecurityKnowledge = {
  packRefs: string[];
};

export type SecurityCredentialRef = string;

export type SecurityAuthenticationProfile = {
  id: string;
  kind: "anonymous" | "bearer" | "basic" | "api_key" | "custom";
  role?: string;
  credentialRef?: SecurityCredentialRef;
};

export type SecurityAttackCategory =
  | "authorization"
  | "authentication"
  | "ssrf"
  | "file_upload"
  | "injection"
  | "path_traversal"
  | "deserialization"
  | "other";

export type SecurityRequestExpectation = {
  outcome: "allow" | "deny" | "error";
  statusCodes?: number[];
};

export type SecurityAttackRequest = {
  pathParameters?: Record<string, string>;
  queryParameters?: Record<string, string>;
  headers?: Record<string, string>;
  body?: unknown;
  expect: SecurityRequestExpectation;
};

export type SecurityAttackProfile = {
  id: string;
  category: SecurityAttackCategory;
  entrypointRef: string;
  authenticationProfileRef: string;
  baseline: SecurityAttackRequest;
  attack: SecurityAttackRequest;
};

export type SecurityRuntimeTarget = {
  id: string;
  entrypointRef: string;
  probeId: string;
  strictLineKey: string;
  purpose: "business-entrypoint" | "sensitive-sink" | "custom";
};

export type SecurityExhaustivenessPolicy = {
  mode: "finite_matrix";
  requireAllCases: true;
  onIncomplete: "blocked";
};

export type SecuritySafetyPolicy = {
  maxConcurrency: number;
  maxRequestsPerSecond: number;
  maxDurationMs: number;
  destructivePayloads: "forbidden";
  stateMutation: "test-tenant-only";
  cleanupRequired: true;
};

export type SecurityVerdictPolicy = {
  failOnSeverity: SecuritySeverity[];
  requireExhaustiveCompletion: true;
  blockedCountsAs: "fail";
};

type SecurityPlanContractBase = {
  suiteType: "security";
  targetBoundary: SecurityTargetBoundary;
  entrypoints: SecurityEntrypoint[];
  authenticationProfiles: SecurityAuthenticationProfile[];
  attackProfiles: SecurityAttackProfile[];
  exhaustiveness: SecurityExhaustivenessPolicy;
  safetyPolicy: SecuritySafetyPolicy;
  verdictPolicy: SecurityVerdictPolicy;
};

export type SecurityPlanContract =
  | (SecurityPlanContractBase & {
      securityMode: "blackbox";
      securityKnowledge: SecurityKnowledge;
      runtimeTargets?: never;
    })
  | (SecurityPlanContractBase & {
      securityMode: "sidecar_assisted";
      securityKnowledge?: SecurityKnowledge;
      runtimeTargets: SecurityRuntimeTarget[];
    });

export type SecurityContractReasonCode =
  | "security_contract_invalid"
  | "security_contract_suite_type_invalid"
  | "security_contract_security_mode_invalid"
  | "security_contract_target_boundary_invalid"
  | "security_contract_knowledge_invalid"
  | "security_contract_entrypoints_invalid"
  | "security_contract_authentication_profiles_invalid"
  | "security_contract_attack_profiles_invalid"
  | "security_contract_runtime_targets_invalid"
  | "security_contract_exhaustiveness_invalid"
  | "security_contract_safety_policy_invalid"
  | "security_contract_verdict_policy_invalid"
  | "security_contract_duplicate_id"
  | "security_contract_reference_invalid"
  | "security_contract_secret_persisted"
  | "security_contract_blackbox_forbidden_field"
  | "security_contract_sidecar_runtime_target_required";

export type SecurityContractValidationResult =
  | { ok: true; contract: SecurityPlanContract }
  | {
      ok: false;
      reasonCode: SecurityContractReasonCode;
      errors: string[];
    };
