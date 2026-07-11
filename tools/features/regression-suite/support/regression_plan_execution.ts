import type {
  BuildPreflightArgs,
  PlanPrerequisite,
  PlanStep,
  PreflightResult,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { deepResolvePlaceholderValue } from "@tools-core/placeholder_resolution";
import {
  applyStepExtract,
  applyStepExtractWithDiagnostics,
  validateStepExtracts,
} from "../shared/regression_step_extract";
import { validateExternalVerificationContract } from "@tools-regression-execution-plan-spec/external_verification_contract.util";
import { resolveWatcherWaitPolicy, validateWatchers } from "../shared/regression_watcher_policy";
import { normalizeHttpContextAliases } from "../shared/regression_http_request";
import { validateCanonicalPlanContextKeys } from "@tools-regression-execution-plan-spec/suite_context_key_validation.util";

import {
  hasDuplicate,
  isStrictProbeKey,
  hasNonBlank,
  emptyPreflightDetails,
  validateStepExpectations,
  validateStepConditions,
  validateTransportPlaceholderSyntax,
  validateCorrelationPolicy,
  classifyPrerequisites,
} from "./regression_plan_preflight_validation";
export function buildReplayPreflight(args: BuildPreflightArgs): PreflightResult {
  const { metadata, contract, providedContext, targetCandidateCount } = args;
  if (args.projectContext?.status === "blocked" && args.projectContext.reasonCode) {
    const isNeedsUserInput =
      args.projectContext.reasonCode === "env_key_missing" ||
      args.projectContext.reasonCode === "script_execution_failed" ||
      args.projectContext.reasonCode === "external_healthcheck_failed" ||
      args.projectContext.reasonCode === "runtime_context_unknown";
    const nextAction =
      typeof args.projectContext.nextAction === "string" &&
      args.projectContext.nextAction.trim().length > 0
        ? args.projectContext.nextAction
        : (args.projectContext.requiredUserAction?.[0] ??
          "Provide required project context input.");
    return {
      status: isNeedsUserInput ? "needs_user_input" : "blocked_invalid",
      reasonCode: args.projectContext.reasonCode,
      ...emptyPreflightDetails(),
      missing: args.projectContext.missing ?? [],
      checks: args.projectContext.checks ?? [],
      nextAction,
      requiredUserAction: args.projectContext.requiredUserAction ?? [nextAction],
    };
  }
  const legacyExpectations = (contract as Record<string, unknown>).expectations;

  if (Array.isArray(legacyExpectations) && legacyExpectations.length > 0) {
    return {
      status: "blocked_invalid",
      reasonCode: "top_level_expectations_unsupported",
      ...emptyPreflightDetails(),
      requiredUserAction: [
        "Move contract.expectations[] into step-scoped steps[].expect[] entries.",
      ],
    };
  }

  if (metadata.execution.intent !== "regression") {
    return {
      status: "blocked_invalid",
      reasonCode: "invalid_execution_intent",
      ...emptyPreflightDetails(),
      requiredUserAction: ["Set metadata.execution.intent to 'regression'."],
    };
  }
  if (!contract.targets.length) {
    return {
      status: "blocked_invalid",
      reasonCode: "target_missing",
      ...emptyPreflightDetails(),
      requiredUserAction: ["Add at least one target in contract.targets."],
    };
  }
  if (!contract.steps.length) {
    return {
      status: "blocked_invalid",
      reasonCode: "steps_missing",
      ...emptyPreflightDetails(),
      requiredUserAction: ["Add at least one step in contract.steps."],
    };
  }

  const stepOrders = contract.steps.map((step) => step.order);
  if (hasDuplicate(stepOrders)) {
    return {
      status: "blocked_invalid",
      reasonCode: "step_order_duplicate",
      ...emptyPreflightDetails(),
      requiredUserAction: ["Ensure each step.order value is unique."],
    };
  }

  const sorted = [...stepOrders].sort((a, b) => a - b);
  for (let i = 0; i < sorted.length; i += 1) {
    if (sorted[i] !== i + 1) {
      return {
        status: "blocked_invalid",
        reasonCode: "step_order_non_sequential",
        ...emptyPreflightDetails(),
        requiredUserAction: ["Ensure steps are sequentially numbered from 1..N."],
      };
    }
  }

  for (const step of contract.steps) {
    if (!(step.protocol in step.transport)) {
      return {
        status: "blocked_invalid",
        reasonCode: "transport_protocol_mismatch",
        ...emptyPreflightDetails(),
        requiredUserAction: [
          `Add transport.${step.protocol} for step '${step.id}' or correct step.protocol.`,
        ],
      };
    }
  }

  const stepExpectValidation = validateStepExpectations(contract.steps);
  if (!stepExpectValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: stepExpectValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: stepExpectValidation.requiredUserAction,
    };
  }
  const stepExtractValidation = validateStepExtracts(contract.steps);
  if (!stepExtractValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: stepExtractValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: stepExtractValidation.requiredUserAction,
    };
  }
  const stepConditionValidation = validateStepConditions(contract.steps);
  if (!stepConditionValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: stepConditionValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: stepConditionValidation.requiredUserAction,
    };
  }
  const transportPlaceholderValidation = validateTransportPlaceholderSyntax(contract.steps);
  if (!transportPlaceholderValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: transportPlaceholderValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: transportPlaceholderValidation.requiredUserAction,
    };
  }
  const canonicalContextKeyValidation = validateCanonicalPlanContextKeys({
    prerequisites: contract.prerequisites,
    steps: contract.steps,
    ...(typeof contract.externalVerification === "undefined"
      ? {}
      : { externalVerification: contract.externalVerification }),
  });
  if (!canonicalContextKeyValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: canonicalContextKeyValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: canonicalContextKeyValidation.requiredUserAction,
    };
  }
  const externalVerificationValidation = validateExternalVerificationContract(
    contract.externalVerification,
  );
  if (!externalVerificationValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: externalVerificationValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: externalVerificationValidation.requiredUserAction,
    };
  }
  const correlationValidation = validateCorrelationPolicy(contract.correlation);
  if (!correlationValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: correlationValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: correlationValidation.requiredUserAction,
    };
  }
  const watcherValidation = validateWatchers(contract.watchers, contract.steps);
  if (!watcherValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: watcherValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: watcherValidation.requiredUserAction,
    };
  }

  if (targetCandidateCount > 1) {
    return {
      status: "blocked_ambiguous",
      reasonCode: "target_ambiguous",
      ...emptyPreflightDetails(),
      requiredUserAction: ["Narrow selectors (for example sourceRoot/signature) to one target."],
    };
  }

  if (metadata.execution.probeVerification && metadata.execution.pinStrictProbeKey) {
    for (const target of contract.targets) {
      const key = target.runtimeVerification?.strictProbeKey;
      if (!key || !isStrictProbeKey(key)) {
        return {
          status: "stale_plan",
          reasonCode: "strict_probe_key_invalid",
          ...emptyPreflightDetails(),
          requiredUserAction: ["Update runtimeVerification.strictProbeKey to Class#method:line."],
        };
      }
    }
  }

  const prerequisiteClassification = classifyPrerequisites({
    prerequisites: contract.prerequisites,
    providedContext,
    discoveryPolicy: metadata.execution.discoveryPolicy,
  });

  if (prerequisiteClassification.type === "blocked_invalid") {
    return {
      status: "blocked_invalid",
      reasonCode: prerequisiteClassification.reasonCode,
      missing: [],
      discoverablePending: [],
      prerequisiteResolution: prerequisiteClassification.resolution,
      requiredUserAction: prerequisiteClassification.requiredUserAction,
    };
  }

  const { missing, discoverablePending, resolution } = prerequisiteClassification;

  if (missing.length > 0 && discoverablePending.length > 0) {
    return {
      status: "needs_user_input",
      reasonCode: "missing_prerequisites_mixed",
      missing,
      discoverablePending,
      checks: [],
      nextAction: `Provide ${missing[0]} and run discovery resolver.`,
      prerequisiteResolution: resolution,
      requiredUserAction: [
        ...missing.map((field) => `Provide ${field}`),
        `Run discovery resolver for: ${discoverablePending.join(", ")}`,
      ],
    };
  }

  if (missing.length > 0) {
    return {
      status: "needs_user_input",
      reasonCode: "missing_prerequisites_user_input",
      missing,
      discoverablePending,
      checks: [],
      nextAction: `Provide ${missing[0]}.`,
      prerequisiteResolution: resolution,
      requiredUserAction: missing.map((field) => `Provide ${field}`),
    };
  }

  if (discoverablePending.length > 0) {
    return {
      status: "needs_discovery",
      reasonCode: "missing_prerequisites_discoverable",
      missing,
      discoverablePending,
      prerequisiteResolution: resolution,
      requiredUserAction: [`Run discovery resolver for: ${discoverablePending.join(", ")}`],
    };
  }

  return {
    status: "ready",
    reasonCode: "ok",
    missing: [],
    discoverablePending: [],
    prerequisiteResolution: resolution,
    requiredUserAction: [],
  };
}

export function resolvePrerequisiteContext(
  prerequisites: PlanPrerequisite[],
  providedContext: Record<string, unknown>,
): Record<string, unknown> {
  const resolved: Record<string, unknown> = {};
  for (const prerequisite of prerequisites) {
    const provided = providedContext[prerequisite.key];
    if (hasNonBlank(provided)) {
      resolved[prerequisite.key] = provided;
      continue;
    }
    if (typeof prerequisite.default !== "undefined") {
      resolved[prerequisite.key] = prerequisite.default;
    }
  }
  return normalizeHttpContextAliases(resolved);
}

export function resolveStepTransport(
  step: PlanStep,
  context: Record<string, unknown>,
): Record<string, unknown> {
  return deepResolvePlaceholderValue(step.transport, context) as Record<string, unknown>;
}
export { applyStepExtract, applyStepExtractWithDiagnostics };
export { resolveWatcherWaitPolicy };

export function buildTimestampRunId(now: Date, _seq: number): string {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  const year = String(now.getFullYear());
  const hour24 = now.getHours();
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const minute = String(now.getMinutes()).padStart(2, "0");
  const second = String(now.getSeconds()).padStart(2, "0");
  const suffix = hour24 >= 12 ? "PM" : "AM";
  const timePart = `${String(hour12).padStart(2, "0")}-${minute}-${second}${suffix}`;
  return `${month}-${day}-${year}-${timePart}`;
}
