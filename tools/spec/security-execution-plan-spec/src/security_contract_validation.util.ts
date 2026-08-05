import type {
  SecurityAttackProfile,
  SecurityContractReasonCode,
  SecurityContractValidationResult,
  SecurityPlanContract,
} from "./models/security_contract.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function isAllowedEntrypointType(value: unknown): boolean {
  return (
    value === "http" ||
    value === "grpc" ||
    value === "message" ||
    value === "file" ||
    value === "scheduled" ||
    value === "internal_runtime"
  );
}

function entrypointType(value: Record<string, unknown>): string | undefined {
  if (isRecord(value.transport) && typeof value.transport.type === "string") {
    return value.transport.type;
  }
  return typeof value.type === "string" ? value.type : undefined;
}

function entrypointHttpShapeIsValid(value: Record<string, unknown>): boolean {
  const transport = isRecord(value.transport) ? value.transport : value;
  return (
    entrypointType(value) === "http" &&
    isNonEmptyString(transport.method) &&
    isNonEmptyString(transport.path) &&
    String(transport.path).startsWith("/") &&
    !String(transport.path).startsWith("//")
  );
}

function isStringRecord(value: unknown): boolean {
  return isRecord(value) && Object.values(value).every((child) => typeof child === "string");
}

function isHttpBaselineRecipe(value: unknown): boolean {
  if (!isRecord(value)) return false;
  if (
    Object.keys(value).some((key) => !["pathParameters", "query", "headers", "body"].includes(key))
  ) {
    return false;
  }
  return (
    (value.pathParameters === undefined || isStringRecord(value.pathParameters)) &&
    (value.query === undefined || isStringRecord(value.query)) &&
    (value.headers === undefined || isStringRecord(value.headers))
  );
}

function isAllowedAuthenticationKind(value: unknown): boolean {
  return (
    value === "anonymous" ||
    value === "bearer" ||
    value === "basic" ||
    value === "api_key" ||
    value === "custom"
  );
}

function isAllowedSeverity(value: unknown): boolean {
  return (
    value === "critical" ||
    value === "high" ||
    value === "medium" ||
    value === "low" ||
    value === "info"
  );
}

function isSecurityRequest(value: unknown): boolean {
  if (!isRecord(value) || !isRecord(value.expect)) return false;
  if (value.transport !== undefined) {
    if (!isRecord(value.transport) || !isAllowedEntrypointType(value.transport.type)) {
      return false;
    }
    if (value.transport.type === "http") {
      if (value.transport.query !== undefined && !isRecord(value.transport.query)) return false;
      if (
        value.transport.queryParameters !== undefined &&
        !isRecord(value.transport.queryParameters)
      ) {
        return false;
      }
    }
  }
  if (
    value.expect.outcome !== "allow" &&
    value.expect.outcome !== "deny" &&
    value.expect.outcome !== "error"
  ) {
    return false;
  }
  const runtimeTargetLists = [
    value.expect.mustHitRuntimeTargets,
    value.expect.mustNotHitRuntimeTargets,
  ];
  if (
    runtimeTargetLists.some(
      (targets) =>
        targets !== undefined &&
        (!Array.isArray(targets) ||
          targets.some((target) => !isNonEmptyString(target)) ||
          new Set(targets).size !== targets.length),
    )
  ) {
    return false;
  }
  return (
    value.expect.statusCodes === undefined ||
    (Array.isArray(value.expect.statusCodes) &&
      value.expect.statusCodes.length > 0 &&
      value.expect.statusCodes.every((code) => isPositiveInteger(code) && code <= 599))
  );
}

function validateRuntimeExpectationReferences(args: {
  attacks: SecurityAttackProfile[];
  runtimeTargets: Array<Record<string, unknown>>;
}): string | null {
  const targetById = new Map<string, string>();
  for (const target of args.runtimeTargets) {
    if (isNonEmptyString(target.id) && isNonEmptyString(target.entrypointRef)) {
      targetById.set(target.id.trim(), target.entrypointRef.trim());
    }
  }
  for (const attack of args.attacks) {
    for (const phase of ["baseline", "attack"] as const) {
      const expectation = attack[phase].expect as SecurityAttackProfile["baseline"]["expect"];
      const targetIds = [
        ...(expectation.mustHitRuntimeTargets ?? []),
        ...(expectation.mustNotHitRuntimeTargets ?? []),
      ];
      for (const targetId of targetIds) {
        const targetEntrypoint = targetById.get(targetId);
        if (!targetEntrypoint) {
          return `attackProfiles.${attack.id}.${phase}.expect references unknown runtime target '${targetId}'`;
        }
        if (targetEntrypoint !== attack.entrypointRef) {
          return `attackProfiles.${attack.id}.${phase}.expect runtime target '${targetId}' belongs to a different entrypoint`;
        }
      }
    }
  }
  return null;
}

const BLACKBOX_FORBIDDEN_KEYS = new Set([
  "source",
  "sourcecode",
  "sourceroot",
  "jar",
  "classpath",
  "fqcn",
  "classname",
  "methodref",
  "probe",
  "probes",
  "probeid",
  "strictlinekey",
  "sidecar",
  "sidecaragent",
  "runtimetargets",
  "musthitruntimetargets",
  "mustnothitruntimetargets",
  "instrumentationtargets",
  "instrumentationtargetref",
  "runtimeinstanceid",
  "internalruntime",
  "dependency",
  "dependencies",
]);

const SENSITIVE_PERSISTED_KEYS = new Set([
  "password",
  "passwd",
  "passphrase",
  "token",
  "secret",
  "apikey",
  "authkey",
  "credential",
  "credentials",
  "authorization",
  "proxyauthorization",
  "cookie",
  "setcookie",
  "accesskey",
  "clientsecret",
  "privatekey",
  "resolvedcredential",
  "env",
  "rawenvironment",
]);
const SENSITIVE_PERSISTED_KEY_PATTERN =
  /(?:password|passwd|passphrase|token|secret|apikey|authkey|authtoken|credential|cookie|accesskey|clientsecret|privatekey|resolved|env)/;

const UNRESOLVED_REFERENCE_PATTERN = /^\$\{[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)+\}$/;
const UNRESOLVED_AUTH_VALUE_PATTERN =
  /^(?:Bearer|Basic)\s+\$\{[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)+\}$/i;
const SYMBOLIC_CREDENTIAL_REFERENCE_PATTERN =
  /^[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)+$/;
const RESOLVED_SECRET_STRING_PATTERN =
  /\b(?:bearer|basic)\s+\S+|\b(?:password|passwd|passphrase|token|secret|api[_-]?key|authorization|cookie)\s*[:=]\s*(?!\$\{[^}]+\})\S+|\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b/i;

function isUnresolvedSecretValue(value: unknown): value is string {
  return (
    typeof value === "string" &&
    (UNRESOLVED_REFERENCE_PATTERN.test(value.trim()) ||
      UNRESOLVED_AUTH_VALUE_PATTERN.test(value.trim()))
  );
}

function isSymbolicCredentialReference(value: unknown): value is string {
  return typeof value === "string" && SYMBOLIC_CREDENTIAL_REFERENCE_PATTERN.test(value.trim());
}

function findPersistedSecretViolation(
  value: unknown,
  fieldPath: string,
  sensitiveContext = false,
): string | null {
  if (
    sensitiveContext &&
    value !== null &&
    typeof value !== "string" &&
    !Array.isArray(value) &&
    !isRecord(value)
  ) {
    return fieldPath;
  }
  if (typeof value === "string") {
    if (sensitiveContext) return isUnresolvedSecretValue(value) ? null : fieldPath;
    return RESOLVED_SECRET_STRING_PATTERN.test(value) ? fieldPath : null;
  }
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      const violation = findPersistedSecretViolation(
        value[index],
        `${fieldPath}[${index}]`,
        sensitiveContext,
      );
      if (violation) return violation;
    }
    return null;
  }
  if (!isRecord(value)) return null;

  for (const [key, nestedValue] of Object.entries(value)) {
    const normalizedKey = key.replaceAll(/[^A-Za-z0-9]/g, "").toLowerCase();
    const nestedPath = `${fieldPath}.${key}`;
    if (normalizedKey === "credentialref") {
      if (!isSymbolicCredentialReference(nestedValue)) return nestedPath;
      continue;
    }
    const isTargetBoundaryEnvironment = nestedPath === "contract.targetBoundary.environment";
    const isSensitiveKey =
      SENSITIVE_PERSISTED_KEYS.has(normalizedKey) ||
      SENSITIVE_PERSISTED_KEY_PATTERN.test(normalizedKey);
    if (!isTargetBoundaryEnvironment && isSensitiveKey) {
      const violation = findPersistedSecretViolation(nestedValue, nestedPath, true);
      if (violation) return violation;
      continue;
    }
    const violation = findPersistedSecretViolation(nestedValue, nestedPath, sensitiveContext);
    if (violation) return violation;
  }
  return null;
}

function findBlackboxViolation(value: unknown, fieldPath: string): string | null {
  if (typeof value === "string") {
    if (
      /\b(?:sidecar(?:\s+agent)?|probes?|strict\s+line\s+key|fqcn|classpath|source\s+code|jars?)\b/i.test(
        value,
      ) ||
      /\b(?:[a-z_][\w$]*\.){2,}[A-Z][\w$]*(?:\.[\w$]+)*\b/.test(value) ||
      /\b[\w$.]+#[^:\s]+:\d+\b/.test(value)
    ) {
      return fieldPath;
    }
    return null;
  }
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      const violation = findBlackboxViolation(value[index], `${fieldPath}[${index}]`);
      if (violation) return violation;
    }
    return null;
  }
  if (!isRecord(value)) return null;
  for (const [key, nestedValue] of Object.entries(value)) {
    const normalizedKey = key.replaceAll(/[^A-Za-z0-9]/g, "").toLowerCase();
    if (BLACKBOX_FORBIDDEN_KEYS.has(normalizedKey)) {
      return `${fieldPath}.${key}`;
    }
    const violation = findBlackboxViolation(nestedValue, `${fieldPath}.${key}`);
    if (violation) return violation;
  }
  return null;
}

function invalid(
  reasonCode: SecurityContractReasonCode,
  errors: string[],
): SecurityContractValidationResult {
  return { ok: false, reasonCode, errors };
}

function validateAttackProfileReferences(
  attacks: SecurityAttackProfile[],
  entrypointIds: Set<string>,
  authIds: Set<string>,
): string | null {
  for (const attack of attacks) {
    if (!entrypointIds.has(attack.entrypointRef))
      return `attackProfiles.${attack.id}.entrypointRef`;
    if (!authIds.has(attack.authenticationProfileRef)) {
      return `attackProfiles.${attack.id}.authenticationProfileRef`;
    }
  }
  return null;
}

export function validateSecurityPlanContract(input: unknown): SecurityContractValidationResult {
  if (!isRecord(input)) return invalid("security_contract_invalid", ["contract must be an object"]);
  if (input.suiteType !== "security") {
    return invalid("security_contract_suite_type_invalid", ["suiteType must be 'security'"]);
  }
  if (input.securityMode !== "blackbox" && input.securityMode !== "sidecar_assisted") {
    return invalid("security_contract_security_mode_invalid", [
      "securityMode must be blackbox|sidecar_assisted",
    ]);
  }

  const boundary = input.targetBoundary;
  if (
    !isRecord(boundary) ||
    boundary.environment !== "local-ci" ||
    boundary.externalNetworkAccess !== "forbidden" ||
    !Array.isArray(boundary.allowedHosts) ||
    boundary.allowedHosts.length === 0 ||
    boundary.allowedHosts.some((value) => !isNonEmptyString(value)) ||
    !Array.isArray(boundary.allowedPorts) ||
    boundary.allowedPorts.length === 0 ||
    boundary.allowedPorts.some((value) => !isPositiveInteger(value) || value > 65535)
  ) {
    return invalid("security_contract_target_boundary_invalid", [
      "targetBoundary must declare local-ci, forbidden external network access, allowed hosts, and ports",
    ]);
  }

  if (input.securityKnowledge !== undefined) {
    const knowledge = input.securityKnowledge;
    if (
      !isRecord(knowledge) ||
      (knowledge.packRefs !== undefined &&
        (!Array.isArray(knowledge.packRefs) ||
          knowledge.packRefs.length === 0 ||
          knowledge.packRefs.some((value) => !isNonEmptyString(value))))
    ) {
      return invalid("security_contract_knowledge_invalid", [
        "securityKnowledge.packRefs must contain non-empty pinned references when provided",
      ]);
    }
  }
  if (
    input.securityMode === "blackbox" &&
    isRecord(input.securityKnowledge) &&
    Array.isArray(input.securityKnowledge.packRefs) &&
    input.securityKnowledge.packRefs.some(
      (ref) => !/^[a-z0-9][a-z0-9-]*@\d+\.\d+\.\d+$/.test(String(ref)),
    )
  ) {
    return invalid("security_contract_knowledge_invalid", [
      "blackbox securityKnowledge.packRefs must use a pinned pack@major.minor.patch reference",
    ]);
  }

  if (input.securityMode === "blackbox" && isRecord(boundary)) {
    if (!isNonEmptyString(boundary.baseUrl)) {
      return invalid("security_contract_target_boundary_invalid", [
        "blackbox targetBoundary.baseUrl is required for HTTP execution",
      ]);
    }
    try {
      const baseUrl = new URL(boundary.baseUrl);
      const allowedHosts = boundary.allowedHosts as unknown[];
      const allowedPorts = boundary.allowedPorts as unknown[];
      let port = 80;
      if (baseUrl.protocol === "https:") port = 443;
      if (baseUrl.port) port = Number(baseUrl.port);
      if (
        (baseUrl.protocol !== "http:" && baseUrl.protocol !== "https:") ||
        !allowedHosts.includes(baseUrl.hostname) ||
        !allowedPorts.includes(port)
      ) {
        return invalid("security_contract_target_boundary_invalid", [
          "blackbox baseUrl must use HTTP(S) and match an allowed host and port",
        ]);
      }
    } catch {
      return invalid("security_contract_target_boundary_invalid", [
        "blackbox targetBoundary.baseUrl must be a valid HTTP(S) URL",
      ]);
    }
  }

  if (!Array.isArray(input.entrypoints) || input.entrypoints.length === 0) {
    return invalid("security_contract_entrypoints_invalid", ["entrypoints must be non-empty"]);
  }
  const entrypointIds = input.entrypoints.map((entry) =>
    isRecord(entry) && isNonEmptyString(entry.id) ? entry.id.trim() : "",
  );
  if (
    entrypointIds.some((id) => !id) ||
    new Set(entrypointIds).size !== entrypointIds.length ||
    input.entrypoints.some(
      (entry) => !isRecord(entry) || !isAllowedEntrypointType(entrypointType(entry)),
    )
  ) {
    return invalid("security_contract_entrypoints_invalid", [
      "entrypoints must have unique non-empty ids",
    ]);
  }
  if (
    input.securityMode === "blackbox" &&
    input.entrypoints.some(
      (entry) =>
        isRecord(entry) &&
        entrypointType(entry) === "http" &&
        entry.baseline !== undefined &&
        !isHttpBaselineRecipe(entry.baseline),
    )
  ) {
    return invalid("security_contract_entrypoints_invalid", [
      "HTTP entrypoint baseline recipes may contain only pathParameters, query, headers, and body",
    ]);
  }

  if (!Array.isArray(input.authenticationProfiles) || input.authenticationProfiles.length === 0) {
    return invalid("security_contract_authentication_profiles_invalid", [
      "authenticationProfiles must be non-empty",
    ]);
  }
  const authIds = input.authenticationProfiles.map((profile) =>
    isRecord(profile) && isNonEmptyString(profile.id) ? profile.id.trim() : "",
  );
  if (
    authIds.some((id) => !id) ||
    new Set(authIds).size !== authIds.length ||
    input.authenticationProfiles.some(
      (profile) => !isRecord(profile) || !isAllowedAuthenticationKind(profile.kind),
    )
  ) {
    return invalid("security_contract_authentication_profiles_invalid", [
      "authenticationProfiles must have unique non-empty ids",
    ]);
  }
  if (
    input.securityMode === "blackbox" &&
    input.authenticationProfiles.some((profile) => {
      if (!isRecord(profile)) return true;
      if (profile.kind === "anonymous") return profile.credentialRef !== undefined;
      return !isSymbolicCredentialReference(profile.credentialRef);
    })
  ) {
    const persistedSecretViolation = findPersistedSecretViolation(input, "contract");
    if (persistedSecretViolation) {
      return invalid("security_contract_secret_persisted", [
        `resolved credentials, tokens, passwords, authorization values, and raw environment values cannot be persisted at ${persistedSecretViolation}; use an unresolved reference placeholder`,
      ]);
    }
    return invalid("security_contract_authentication_profiles_invalid", [
      "blackbox anonymous profiles must not declare credentials and constrained profiles must use symbolic credentialRef values",
    ]);
  }

  if (input.securityMode === "blackbox" && input.attackProfiles !== undefined) {
    return invalid("security_contract_attack_profiles_invalid", [
      "blackbox contracts must use customCases for explicit overrides; attackProfiles are not allowed in normal mode",
    ]);
  }
  const authoredCases =
    input.securityMode === "blackbox"
      ? input.customCases
      : (input.customCases ?? input.attackProfiles);
  if (
    input.securityMode === "sidecar_assisted" &&
    (!Array.isArray(authoredCases) || authoredCases.length === 0)
  ) {
    return invalid("security_contract_attack_profiles_invalid", [
      "sidecar_assisted contracts must define non-empty customCases",
    ]);
  }
  if (authoredCases !== undefined && !Array.isArray(authoredCases)) {
    return invalid("security_contract_attack_profiles_invalid", [
      "customCases must be an array when provided",
    ]);
  }
  const attackIds = (authoredCases ?? []).map((attack) =>
    isRecord(attack) && isNonEmptyString(attack.id) ? attack.id.trim() : "",
  );
  if (attackIds.some((id) => !id) || new Set(attackIds).size !== attackIds.length) {
    return invalid("security_contract_attack_profiles_invalid", [
      "attackProfiles must have unique non-empty ids",
    ]);
  }
  if (
    (authoredCases ?? []).some(
      (attack) =>
        !isRecord(attack) ||
        !isNonEmptyString(attack.entrypointRef) ||
        !isNonEmptyString(attack.authenticationProfileRef) ||
        !isSecurityRequest(attack.attack) ||
        !isSecurityRequest(attack.baseline),
    )
  ) {
    return invalid("security_contract_attack_profiles_invalid", [
      "attackProfiles must define baseline and attack requests with deterministic expectations",
    ]);
  }
  const referenceError = validateAttackProfileReferences(
    (authoredCases ?? []) as SecurityAttackProfile[],
    new Set(entrypointIds),
    new Set(authIds),
  );
  if (referenceError) return invalid("security_contract_reference_invalid", [referenceError]);

  if (
    !isRecord(input.exhaustiveness) ||
    input.exhaustiveness.mode !== "finite_matrix" ||
    input.exhaustiveness.requireAllCases !== true ||
    input.exhaustiveness.onIncomplete !== "blocked"
  ) {
    return invalid("security_contract_exhaustiveness_invalid", [
      "exhaustiveness must require a finite matrix and block incomplete coverage",
    ]);
  }
  if (
    !isRecord(input.safetyPolicy) ||
    !isPositiveInteger(input.safetyPolicy.maxConcurrency) ||
    !isPositiveInteger(input.safetyPolicy.maxRequestsPerSecond) ||
    !isPositiveInteger(input.safetyPolicy.maxDurationMs) ||
    input.safetyPolicy.destructivePayloads !== "forbidden" ||
    input.safetyPolicy.stateMutation !== "test-tenant-only" ||
    input.safetyPolicy.cleanupRequired !== true
  ) {
    return invalid("security_contract_safety_policy_invalid", [
      "safetyPolicy must define positive bounds, forbidden destructive payloads, test-tenant-only mutation, and cleanup",
    ]);
  }
  if (
    !isRecord(input.verdictPolicy) ||
    !Array.isArray(input.verdictPolicy.failOnSeverity) ||
    input.verdictPolicy.failOnSeverity.some((value) => !isAllowedSeverity(value)) ||
    input.verdictPolicy.requireExhaustiveCompletion !== true ||
    input.verdictPolicy.blockedCountsAs !== "fail"
  ) {
    return invalid("security_contract_verdict_policy_invalid", [
      "verdictPolicy must define failing severities and treat blocked coverage as fail",
    ]);
  }

  if (input.securityMode === "blackbox") {
    const blackboxViolation = findBlackboxViolation(input, "contract");
    const internalRuntimeEntrypoint = input.entrypoints.some(
      (entry) => isRecord(entry) && entrypointType(entry) === "internal_runtime",
    );
    if (blackboxViolation || internalRuntimeEntrypoint) {
      return invalid("security_contract_blackbox_forbidden_field", [
        internalRuntimeEntrypoint
          ? "blackbox contracts must not declare internal_runtime entrypoints"
          : `blackbox contracts contain forbidden internal-runtime data at ${blackboxViolation}`,
      ]);
    }
    if (
      input.entrypoints.some(
        (entry) =>
          !isRecord(entry) ||
          (entrypointType(entry) === "http" && !entrypointHttpShapeIsValid(entry)),
      )
    ) {
      return invalid("security_contract_entrypoints_invalid", [
        "HTTP entrypoints must declare a relative path and method inside the HTTP transport",
      ]);
    }
  } else {
    if (!Array.isArray(input.runtimeTargets) || input.runtimeTargets.length === 0) {
      return invalid("security_contract_sidecar_runtime_target_required", [
        "sidecar_assisted contracts must declare runtimeTargets",
      ]);
    }
    const runtimeTargetIds = input.runtimeTargets.map((target) =>
      isRecord(target) && isNonEmptyString(target.id) ? target.id.trim() : "",
    );
    if (
      runtimeTargetIds.some((id) => !id) ||
      new Set(runtimeTargetIds).size !== runtimeTargetIds.length ||
      input.runtimeTargets.some(
        (target) =>
          !isRecord(target) ||
          !isNonEmptyString(target.entrypointRef) ||
          !isNonEmptyString(target.probeId) ||
          !isNonEmptyString(target.strictLineKey) ||
          !/^[$A-Za-z_][$\w]*(?:\.[$A-Za-z_][$\w]*)*#[\w$<>]+:\d+$/.test(target.strictLineKey) ||
          (target.purpose !== "business-entrypoint" &&
            target.purpose !== "sensitive-sink" &&
            target.purpose !== "custom"),
      )
    ) {
      return invalid("security_contract_runtime_targets_invalid", [
        "runtimeTargets must have unique ids and entrypoint, probe, and Strict Line Key references",
      ]);
    }
    const unknownEntrypoint = input.runtimeTargets.find(
      (target) => isRecord(target) && !entrypointIds.includes(String(target.entrypointRef).trim()),
    );
    if (unknownEntrypoint) {
      return invalid("security_contract_reference_invalid", [
        "runtimeTargets.entrypointRef must reference an entrypoint",
      ]);
    }
    if (input.instrumentationTargets !== undefined) {
      if (!Array.isArray(input.instrumentationTargets)) {
        return invalid("security_contract_instrumentation_targets_invalid", [
          "instrumentationTargets must be an array when provided",
        ]);
      }
      const instrumentationTargetIds = input.instrumentationTargets.map((target) =>
        isRecord(target) && isNonEmptyString(target.id) ? target.id.trim() : "",
      );
      if (
        instrumentationTargetIds.some((id) => !id) ||
        new Set(instrumentationTargetIds).size !== instrumentationTargetIds.length ||
        input.instrumentationTargets.some(
          (target) =>
            !isRecord(target) ||
            !isNonEmptyString(target.id) ||
            (target.scope !== "application" && target.scope !== "dependency") ||
            !isNonEmptyString(target.classFqcn) ||
            !/^[$A-Za-z_][$\w]*(?:\.[$A-Za-z_][$\w]*)*$/.test(target.classFqcn) ||
            (target.scope === "dependency" && !isNonEmptyString(target.dependencyRef)) ||
            (target.scope === "application" && target.dependencyRef !== undefined),
        )
      ) {
        return invalid("security_contract_instrumentation_targets_invalid", [
          "instrumentationTargets must declare unique application/dependency scopes and valid FQCNs; dependency targets require dependencyRef",
        ]);
      }
      const instrumentationTargetIdSet = new Set(instrumentationTargetIds);
      const referencedTargetIds = new Set<string>();
      for (const target of input.runtimeTargets) {
        if (!isRecord(target) || target.instrumentationTargetRef === undefined) continue;
        if (!isNonEmptyString(target.instrumentationTargetRef)) {
          return invalid("security_contract_instrumentation_targets_invalid", [
            "runtimeTargets.instrumentationTargetRef must be a non-empty instrumentation target id",
          ]);
        }
        const targetRef = target.instrumentationTargetRef.trim();
        if (!instrumentationTargetIdSet.has(targetRef)) {
          return invalid("security_contract_reference_invalid", [
            `runtime target '${String(target.id)}' references unknown instrumentation target '${targetRef}'`,
          ]);
        }
        referencedTargetIds.add(targetRef);
      }
      const unreferencedTarget = instrumentationTargetIds.find(
        (id) => !referencedTargetIds.has(id),
      );
      if (unreferencedTarget) {
        return invalid("security_contract_instrumentation_targets_invalid", [
          `instrumentation target '${unreferencedTarget}' is not linked to a runtime target`,
        ]);
      }
    } else if (
      input.runtimeTargets.some(
        (target) => isRecord(target) && target.instrumentationTargetRef !== undefined,
      )
    ) {
      return invalid("security_contract_instrumentation_targets_invalid", [
        "runtimeTargets.instrumentationTargetRef requires instrumentationTargets",
      ]);
    }
    const runtimeExpectationReferenceError = validateRuntimeExpectationReferences({
      attacks: (authoredCases ?? []) as SecurityAttackProfile[],
      runtimeTargets: input.runtimeTargets.filter(isRecord),
    });
    if (runtimeExpectationReferenceError) {
      return invalid("security_contract_reference_invalid", [runtimeExpectationReferenceError]);
    }
  }

  const persistedSecretViolation = findPersistedSecretViolation(input, "contract");
  if (persistedSecretViolation) {
    return invalid("security_contract_secret_persisted", [
      `resolved credentials, tokens, passwords, authorization values, and raw environment values cannot be persisted at ${persistedSecretViolation}; use an unresolved reference placeholder`,
    ]);
  }

  return { ok: true, contract: input as unknown as SecurityPlanContract };
}
