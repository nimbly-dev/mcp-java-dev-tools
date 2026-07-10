import type {
  PlanExternalVerification,
  PlanPrerequisite,
  PlanStep,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { normalizePlaceholderSyntaxInString } from "@tools-core/placeholder_resolution";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function looksLikeEnvVariableKey(key: string): boolean {
  return /^[A-Z][A-Z0-9_]*$/.test(key.trim());
}

function findNoncanonicalTransportPlaceholder(args: {
  value: unknown;
  fieldPath: string;
}): { fieldPath: string; key: string } | null {
  if (typeof args.value === "string") {
    const normalized = normalizePlaceholderSyntaxInString(args.value);
    const keys = [...normalized.normalized.matchAll(/\$\{([^}]+)\}/g)].map((match) => String(match[1] ?? "").trim());
    const invalidKey = keys.find((key) => looksLikeEnvVariableKey(key));
    return invalidKey ? { fieldPath: args.fieldPath, key: invalidKey } : null;
  }
  if (Array.isArray(args.value)) {
    for (let index = 0; index < args.value.length; index += 1) {
      const invalid = findNoncanonicalTransportPlaceholder({
        value: args.value[index],
        fieldPath: `${args.fieldPath}[${index}]`,
      });
      if (invalid) return invalid;
    }
    return null;
  }
  if (isRecord(args.value)) {
    for (const [key, entry] of Object.entries(args.value)) {
      const invalid = findNoncanonicalTransportPlaceholder({
        value: entry,
        fieldPath: `${args.fieldPath}.${key}`,
      });
      if (invalid) return invalid;
    }
  }
  return null;
}

export function validateCanonicalPlanContextKeys(args: {
  prerequisites: PlanPrerequisite[];
  steps: PlanStep[];
  externalVerification?: PlanExternalVerification[];
}):
  | { ok: true }
  | {
      ok: false;
      reasonCode: "plan_context_key_noncanonical";
      requiredUserAction: string[];
    } {
  for (const prerequisite of args.prerequisites) {
    if (looksLikeEnvVariableKey(prerequisite.key)) {
      return {
        ok: false,
        reasonCode: "plan_context_key_noncanonical",
        requiredUserAction: [
          `Replace prerequisite key '${prerequisite.key}' with a canonical context key (for example auth.bearer or apiBaseUrl). Raw .env variable names belong only in project context env mappings.`,
        ],
      };
    }
  }
  for (const step of args.steps) {
    const invalid = findNoncanonicalTransportPlaceholder({
      value: step.transport,
      fieldPath: "transport",
    });
    if (invalid) {
      return {
        ok: false,
        reasonCode: "plan_context_key_noncanonical",
        requiredUserAction: [
          `Replace non-canonical placeholder key '${invalid.key}' in step '${step.id}' at '${invalid.fieldPath}' with a canonical context key. Raw .env variable names belong only in project context env mappings.`,
        ],
      };
    }
  }
  for (const verification of args.externalVerification ?? []) {
    if (
      !isRecord(verification) ||
      !isRecord(verification.provider) ||
      typeof verification.provider.type !== "string" ||
      !isRecord(verification.request)
    ) {
      continue;
    }
    const providerType = verification.provider.type;
    if (providerType === "http") {
      const invalid = findNoncanonicalTransportPlaceholder({
        value: verification.request.http,
        fieldPath: "request.http",
      });
      if (invalid) {
        return {
          ok: false,
          reasonCode: "plan_context_key_noncanonical",
          requiredUserAction: [
            `Replace non-canonical placeholder key '${invalid.key}' in external verification '${verification.id}' at '${invalid.fieldPath}' with a canonical context key. Raw .env variable names belong only in project context env mappings.`,
          ],
        };
      }
    }
    if (providerType === "sql") {
      const parameters = verification.request.sql ? verification.request.sql.parameters ?? [] : [];
      for (const parameter of parameters) {
        const key = parameter.valueFromContext;
        if (typeof key === "string" && looksLikeEnvVariableKey(key)) {
          return {
            ok: false,
            reasonCode: "plan_context_key_noncanonical",
            requiredUserAction: [
              `Replace non-canonical SQL valueFromContext key '${key}' in external verification '${verification.id}' with a canonical context key. Raw .env variable names belong only in project context env mappings.`,
            ],
          };
        }
      }
    }
  }
  return { ok: true };
}
