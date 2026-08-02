import { promises as fs } from "node:fs";
import path from "node:path";

import type {
  SecurityAttackCategory,
  SecurityAuthenticationProfile,
  SecurityEntrypointType,
  SecuritySeverity,
} from "@tools-security-execution-plan-spec";

type SecurityVersion = { major: number; minor: number; patch: number };

type SecurityAuthenticationKind = SecurityAuthenticationProfile["kind"];

export type SecurityKnowledgeCaseTemplate = {
  id: string;
  baseline: {
    expectedOutcome: "allow";
    statusCodes: number[];
  };
  attack: {
    mutation:
      | "own-versus-foreign-resource"
      | "anonymous-boundary"
      | "missing-authentication"
      | "malformed-authentication"
      | "constrained-role"
      | "bounded-path-parameter"
      | "local-callback-url"
      | "safe-upload-content-type"
      | "bounded-input"
      | "non-executing-serialized-value";
    mutationBoundary: "path_parameter" | "query_parameter" | "header" | "body";
    payloadTemplates: string[];
    expectedOutcome: "deny" | "error";
    statusCodes: number[];
  };
};

export type SecurityBlackboxKnowledgeRule = {
  id: string;
  cwe: string[];
  rationale: string;
  categories: SecurityAttackCategory[] | ["*"];
  entrypointTypes: SecurityEntrypointType[];
  applicability: {
    authenticationKinds: SecurityAuthenticationKind[];
    requiredFixtureContextKeys: string[];
  };
  caseTemplates: SecurityKnowledgeCaseTemplate[];
  safety: {
    mutationBoundary: "path_parameter" | "query_parameter" | "header" | "body";
    cleanup: "not_required" | "required";
    maxImpact: "read_only" | "test_tenant_only";
  };
  evidence: {
    required: Array<"baseline_http_response" | "attack_http_response">;
    confirmation: "external_request_response";
  };
  severity: SecuritySeverity;
  reasonCodes: {
    passed: string;
    confirmed: string;
    notApplicable: string;
    blocked: string;
  };
  title: string;
};

type SecurityKnowledgePackManifest = {
  schemaVersion: "1.0.0";
  id: string;
  version: string;
  ref: string;
  rulesFile: string;
  description: string;
  ruleIds: string[];
  compatibility: {
    contractVersionRange: string;
  };
};

export type SecurityBlackboxKnowledgePack = SecurityKnowledgePackManifest & {
  rules: SecurityBlackboxKnowledgeRule[];
};

type SecurityKnowledgeLoadFailure = {
  ok: false;
  reasonCode:
    | "security_knowledge_pack_reference_invalid"
    | "security_knowledge_pack_unavailable"
    | "security_knowledge_pack_malformed"
    | "security_knowledge_pack_duplicate"
    | "security_knowledge_pack_incompatible";
  refs: string[];
  errors?: string[];
};

type PackReadResult =
  | { ok: true; pack: SecurityBlackboxKnowledgePack }
  | { ok: false; ref: string; error: string };

const PACK_REF_PATTERN = /^([a-z0-9][a-z0-9-]*)@(\d+\.\d+\.\d+)$/;
const PACK_ID_PATTERN = /^[a-z0-9][a-z0-9-]*$/;
const VERSION_PATTERN = /^(\d+)\.(\d+)\.(\d+)$/;
const REASON_CODE_PATTERN = /^security_[a-z0-9_]+$/;
const PAYLOAD_TEMPLATE_PATTERN = /^\$\{[A-Za-z][A-Za-z0-9_.-]*\}$/;
const SECURITY_ATTACK_CATEGORIES = new Set<SecurityAttackCategory>([
  "authorization",
  "authentication",
  "ssrf",
  "file_upload",
  "injection",
  "path_traversal",
  "deserialization",
  "other",
]);
const SECURITY_ENTRYPOINT_TYPES = new Set<SecurityEntrypointType>([
  "http",
  "grpc",
  "message",
  "file",
  "scheduled",
  "internal_runtime",
]);
const SECURITY_AUTHENTICATION_KINDS = new Set<SecurityAuthenticationKind>([
  "anonymous",
  "bearer",
  "basic",
  "api_key",
  "custom",
]);
const MUTATION_BOUNDARIES = new Set(["path_parameter", "query_parameter", "header", "body"]);
const MUTATIONS = new Set([
  "own-versus-foreign-resource",
  "anonymous-boundary",
  "missing-authentication",
  "malformed-authentication",
  "constrained-role",
  "bounded-path-parameter",
  "local-callback-url",
  "safe-upload-content-type",
  "bounded-input",
  "non-executing-serialized-value",
]);

function parseVersion(value: string): SecurityVersion | undefined {
  const match = VERSION_PATTERN.exec(value.trim());
  if (!match) return undefined;
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

function compareVersions(left: SecurityVersion, right: SecurityVersion): number {
  if (left.major !== right.major) return left.major - right.major;
  if (left.minor !== right.minor) return left.minor - right.minor;
  return left.patch - right.patch;
}

function satisfiesComparator(version: SecurityVersion, comparator: string): boolean {
  const match = /^(>=|<=|>|<|=|\^|~)?(\d+\.\d+\.\d+)$/.exec(comparator.trim());
  if (!match) return false;
  const targetValue = match[2];
  if (!targetValue) return false;
  const target = parseVersion(targetValue);
  if (!target) return false;
  const operator = match[1] ?? "=";
  const comparison = compareVersions(version, target);
  if (operator === ">=") return comparison >= 0;
  if (operator === "<=") return comparison <= 0;
  if (operator === ">") return comparison > 0;
  if (operator === "<") return comparison < 0;
  if (operator === "^") return version.major === target.major && comparison >= 0;
  if (operator === "~") {
    return version.major === target.major && version.minor === target.minor && comparison >= 0;
  }
  return comparison === 0;
}

function isValidVersionRange(value: unknown): value is string {
  if (typeof value !== "string" || value.trim().length === 0) return false;
  if (value.trim() === "*") return true;
  return value
    .trim()
    .split(/\s+/)
    .every((comparator) => /^(>=|<=|>|<|=|\^|~)?\d+\.\d+\.\d+$/.test(comparator));
}

function satisfiesVersionRange(versionValue: string, range: string): boolean {
  const version = parseVersion(versionValue);
  if (!version) return false;
  const comparators = range.trim() === "*" ? [] : range.trim().split(/\s+/);
  return comparators.every((comparator) => satisfiesComparator(version, comparator));
}

function defaultKnowledgePackRoot(): string {
  return path.join(__dirname, "..", "knowledge-packs");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const allowedKeys = new Set(allowed);
  return Object.keys(value).every((key) => allowedKeys.has(key));
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isPositiveStatusCode(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 100 && value <= 599;
}

function isUniqueNonEmptyStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.every(isNonEmptyString) &&
    new Set(value).size === value.length
  );
}

function isSafePackFileName(value: unknown): value is string {
  return typeof value === "string" && value === path.basename(value) && !value.includes("\\");
}

function validateCaseTemplate(value: unknown): value is SecurityKnowledgeCaseTemplate {
  if (!isRecord(value) || !hasOnlyKeys(value, ["id", "baseline", "attack"])) return false;
  if (!isNonEmptyString(value.id) || !isRecord(value.baseline) || !isRecord(value.attack)) {
    return false;
  }
  if (
    !hasOnlyKeys(value.baseline, ["expectedOutcome", "statusCodes"]) ||
    value.baseline.expectedOutcome !== "allow" ||
    !Array.isArray(value.baseline.statusCodes) ||
    value.baseline.statusCodes.length === 0 ||
    !value.baseline.statusCodes.every(isPositiveStatusCode) ||
    !hasOnlyKeys(value.attack, [
      "mutation",
      "mutationBoundary",
      "payloadTemplates",
      "expectedOutcome",
      "statusCodes",
    ]) ||
    typeof value.attack.mutation !== "string" ||
    !MUTATIONS.has(value.attack.mutation) ||
    typeof value.attack.mutationBoundary !== "string" ||
    !MUTATION_BOUNDARIES.has(value.attack.mutationBoundary) ||
    !Array.isArray(value.attack.payloadTemplates) ||
    value.attack.payloadTemplates.length === 0 ||
    value.attack.payloadTemplates.some(
      (payload) => typeof payload !== "string" || !PAYLOAD_TEMPLATE_PATTERN.test(payload),
    ) ||
    (value.attack.expectedOutcome !== "deny" && value.attack.expectedOutcome !== "error") ||
    !Array.isArray(value.attack.statusCodes) ||
    value.attack.statusCodes.length === 0 ||
    !value.attack.statusCodes.every(isPositiveStatusCode)
  ) {
    return false;
  }
  return true;
}

function validateRule(value: unknown): value is SecurityBlackboxKnowledgeRule {
  if (!isRecord(value)) return false;
  if (
    !hasOnlyKeys(value, [
      "id",
      "cwe",
      "rationale",
      "categories",
      "entrypointTypes",
      "applicability",
      "caseTemplates",
      "safety",
      "evidence",
      "severity",
      "reasonCodes",
      "title",
    ]) ||
    !isNonEmptyString(value.id) ||
    !isUniqueNonEmptyStringArray(value.cwe) ||
    value.cwe.some((cwe) => !/^CWE-\d+$/.test(cwe)) ||
    !isNonEmptyString(value.rationale) ||
    !Array.isArray(value.categories) ||
    value.categories.length === 0 ||
    (value.categories[0] !== "*" &&
      value.categories.some(
        (category) =>
          typeof category !== "string" ||
          !SECURITY_ATTACK_CATEGORIES.has(category as SecurityAttackCategory),
      )) ||
    (value.categories[0] === "*" &&
      (value.categories.length !== 1 || value.categories.some((category) => category !== "*"))) ||
    !isUniqueNonEmptyStringArray(value.entrypointTypes) ||
    value.entrypointTypes.some(
      (entrypointType) => !SECURITY_ENTRYPOINT_TYPES.has(entrypointType as SecurityEntrypointType),
    ) ||
    !isRecord(value.applicability) ||
    !hasOnlyKeys(value.applicability, ["authenticationKinds", "requiredFixtureContextKeys"]) ||
    !isUniqueNonEmptyStringArray(value.applicability.authenticationKinds) ||
    value.applicability.authenticationKinds.some(
      (kind) => !SECURITY_AUTHENTICATION_KINDS.has(kind as SecurityAuthenticationKind),
    ) ||
    !Array.isArray(value.applicability.requiredFixtureContextKeys) ||
    value.applicability.requiredFixtureContextKeys.some((key) => !isNonEmptyString(key)) ||
    !Array.isArray(value.caseTemplates) ||
    value.caseTemplates.length === 0 ||
    value.caseTemplates.some((template) => !validateCaseTemplate(template)) ||
    new Set(value.caseTemplates.map((template) => (isRecord(template) ? template.id : ""))).size !==
      value.caseTemplates.length ||
    !isRecord(value.safety) ||
    !hasOnlyKeys(value.safety, ["mutationBoundary", "cleanup", "maxImpact"]) ||
    typeof value.safety.mutationBoundary !== "string" ||
    !MUTATION_BOUNDARIES.has(value.safety.mutationBoundary) ||
    (value.safety.cleanup !== "not_required" && value.safety.cleanup !== "required") ||
    (value.safety.maxImpact !== "read_only" && value.safety.maxImpact !== "test_tenant_only") ||
    !isRecord(value.evidence) ||
    !hasOnlyKeys(value.evidence, ["required", "confirmation"]) ||
    !Array.isArray(value.evidence.required) ||
    value.evidence.required.length === 0 ||
    value.evidence.required.some(
      (entry) => entry !== "baseline_http_response" && entry !== "attack_http_response",
    ) ||
    value.evidence.confirmation !== "external_request_response" ||
    !isNonEmptyString(value.severity) ||
    !["critical", "high", "medium", "low", "info"].includes(value.severity) ||
    !isRecord(value.reasonCodes) ||
    !hasOnlyKeys(value.reasonCodes, ["passed", "confirmed", "notApplicable", "blocked"]) ||
    Object.values(value.reasonCodes).some(
      (reasonCode) => typeof reasonCode !== "string" || !REASON_CODE_PATTERN.test(reasonCode),
    ) ||
    !isNonEmptyString(value.title)
  ) {
    return false;
  }
  return true;
}

async function readPack(packRoot: string, directoryName: string): Promise<PackReadResult> {
  const packDirectory = path.join(packRoot, directoryName);
  let manifestValue: unknown;
  try {
    manifestValue = JSON.parse(
      await fs.readFile(path.join(packDirectory, "manifest.json"), "utf8"),
    );
  } catch {
    return { ok: false, ref: directoryName, error: "manifest.json could not be read or parsed" };
  }
  if (
    !isRecord(manifestValue) ||
    !hasOnlyKeys(manifestValue, [
      "schemaVersion",
      "id",
      "version",
      "ref",
      "rulesFile",
      "description",
      "ruleIds",
      "compatibility",
    ]) ||
    manifestValue.schemaVersion !== "1.0.0" ||
    !isNonEmptyString(manifestValue.id) ||
    !PACK_ID_PATTERN.test(manifestValue.id) ||
    manifestValue.id !== directoryName ||
    !isNonEmptyString(manifestValue.version) ||
    !parseVersion(manifestValue.version) ||
    !isNonEmptyString(manifestValue.ref) ||
    manifestValue.ref !== `${manifestValue.id}@${manifestValue.version}` ||
    !isSafePackFileName(manifestValue.rulesFile) ||
    !isNonEmptyString(manifestValue.description) ||
    !isUniqueNonEmptyStringArray(manifestValue.ruleIds) ||
    !isRecord(manifestValue.compatibility) ||
    !hasOnlyKeys(manifestValue.compatibility, ["contractVersionRange"]) ||
    !isValidVersionRange(manifestValue.compatibility.contractVersionRange)
  ) {
    return { ok: false, ref: directoryName, error: "manifest.json has an invalid schema" };
  }
  const manifest = manifestValue as {
    schemaVersion: "1.0.0";
    id: string;
    version: string;
    ref: string;
    rulesFile: string;
    description: string;
    ruleIds: string[];
    compatibility: { contractVersionRange: string };
  };

  let rulesValue: unknown;
  try {
    rulesValue = JSON.parse(
      await fs.readFile(path.join(packDirectory, manifest.rulesFile), "utf8"),
    );
  } catch {
    return { ok: false, ref: manifest.ref, error: "rules file could not be read or parsed" };
  }
  if (
    !Array.isArray(rulesValue) ||
    rulesValue.length === 0 ||
    rulesValue.some((rule) => !validateRule(rule))
  ) {
    return { ok: false, ref: manifest.ref, error: "rules file has an invalid schema" };
  }
  const rules = rulesValue as SecurityBlackboxKnowledgeRule[];
  const ruleIds = rules.map((rule) => rule.id);
  if (
    new Set(ruleIds).size !== ruleIds.length ||
    ruleIds.length !== manifest.ruleIds.length ||
    ruleIds.some((ruleId) => !manifest.ruleIds.includes(ruleId))
  ) {
    return {
      ok: false,
      ref: manifest.ref,
      error: "manifest ruleIds do not match the rules file",
    };
  }

  return {
    ok: true,
    pack: {
      schemaVersion: "1.0.0",
      id: manifest.id,
      version: manifest.version,
      ref: manifest.ref,
      rulesFile: manifest.rulesFile,
      description: manifest.description,
      ruleIds: manifest.ruleIds,
      compatibility: {
        contractVersionRange: manifest.compatibility.contractVersionRange,
      },
      rules,
    },
  };
}

export async function loadSecurityBlackboxKnowledgePacks(args: {
  packRefs: string[];
  contractVersion?: string;
  packRootAbs?: string;
}): Promise<{ ok: true; packs: SecurityBlackboxKnowledgePack[] } | SecurityKnowledgeLoadFailure> {
  const invalidRefs = args.packRefs.filter((ref) => !PACK_REF_PATTERN.test(ref));
  if (invalidRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_reference_invalid",
      refs: [...new Set(invalidRefs)].sort(),
    };
  }
  const duplicateRequestedRefs = args.packRefs.filter(
    (ref, index) => args.packRefs.indexOf(ref) !== index,
  );
  if (duplicateRequestedRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_duplicate",
      refs: [...new Set(duplicateRequestedRefs)].sort(),
    };
  }

  const packRoot = args.packRootAbs ?? defaultKnowledgePackRoot();
  const directories = await fs.readdir(packRoot, { withFileTypes: true }).catch(() => []);
  const parsed = await Promise.all(
    directories
      .filter((entry) => entry.isDirectory())
      .sort((left, right) => left.name.localeCompare(right.name))
      .map((entry) => readPack(packRoot, entry.name)),
  );
  const malformed = parsed.filter(
    (entry): entry is Extract<PackReadResult, { ok: false }> => !entry.ok,
  );
  if (malformed.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_malformed",
      refs: malformed.map((entry) => entry.ref).sort(),
      errors: malformed.map((entry) => `${entry.ref}: ${entry.error}`),
    };
  }
  const available = parsed
    .filter((entry): entry is Extract<PackReadResult, { ok: true }> => entry.ok)
    .map((entry) => entry.pack);
  const duplicateCatalogRefs = [
    ...available.map((pack) => pack.ref).filter((ref, index, refs) => refs.indexOf(ref) !== index),
    ...available.map((pack) => pack.id).filter((id, index, ids) => ids.indexOf(id) !== index),
  ];
  if (duplicateCatalogRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_duplicate",
      refs: [...new Set(duplicateCatalogRefs)].sort(),
    };
  }
  const unavailableRefs = args.packRefs.filter(
    (ref) => !available.some((pack) => pack.ref === ref),
  );
  if (unavailableRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_unavailable",
      refs: [...new Set(unavailableRefs)].sort(),
    };
  }
  const contractVersion = args.contractVersion ?? "1.0.0";
  const selected = args.packRefs.map((ref) => available.find((pack) => pack.ref === ref)!);
  const incompatibleRefs = selected
    .filter(
      (pack) => !satisfiesVersionRange(contractVersion, pack.compatibility.contractVersionRange),
    )
    .map((pack) => pack.ref);
  if (incompatibleRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_incompatible",
      refs: [...new Set(incompatibleRefs)].sort(),
    };
  }
  return { ok: true, packs: selected };
}

export function findApplicableSecurityBlackboxRule(args: {
  packs: SecurityBlackboxKnowledgePack[];
  category: SecurityAttackCategory;
  entrypointType: SecurityEntrypointType;
  authenticationKind: SecurityAuthenticationKind;
  fixtureContextKeys?: string[];
}): SecurityBlackboxKnowledgeRule | undefined {
  const fixtureContextKeys = new Set(args.fixtureContextKeys ?? []);
  return args.packs
    .flatMap((pack) => pack.rules)
    .filter((rule) => {
      const categories: readonly string[] = rule.categories;
      const categoryMatches = categories[0] === "*" || categories.includes(args.category);
      const entrypointMatches = rule.entrypointTypes.includes(args.entrypointType);
      const authenticationMatches = rule.applicability.authenticationKinds.includes(
        args.authenticationKind,
      );
      const fixtureMatches = rule.applicability.requiredFixtureContextKeys.every((key) =>
        fixtureContextKeys.has(key),
      );
      return categoryMatches && entrypointMatches && authenticationMatches && fixtureMatches;
    })
    .sort((left, right) => {
      const leftIsWildcard = left.categories[0] === "*";
      const rightIsWildcard = right.categories[0] === "*";
      if (leftIsWildcard !== rightIsWildcard) return leftIsWildcard ? 1 : -1;
      return left.id.localeCompare(right.id);
    })[0];
}
