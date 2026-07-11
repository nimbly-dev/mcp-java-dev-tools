/**
 * Regression plan preflight validation support.
 */
import type {
  PlanCorrelationPolicy,
  PlanStepCondition,
  PlanStepConditionPredicate,
  PlanStepExpectation,
  PlanPrerequisite,
  PrerequisiteResolution,
  PlanStep,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { normalizePlaceholderSyntaxInString } from "@tools-core/placeholder_resolution";

export function hasDuplicate(values: number[]): boolean {
  return new Set(values).size !== values.length;
}

export function isStrictProbeKey(value: string): boolean {
  return /^[\w.$]+#[\w$]+:\d+$/.test(value.trim());
}

export function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function emptyPreflightDetails() {
  return {
    missing: [] as string[],
    discoverablePending: [] as string[],
    checks: [] as string[],
    prerequisiteResolution: [] as PrerequisiteResolution[],
  };
}

function isExpectationOperator(value: string): boolean {
  return (
    value === "field_equals" ||
    value === "field_exists" ||
    value === "field_matches_regex" ||
    value === "numeric_gte" ||
    value === "numeric_lte" ||
    value === "contains" ||
    value === "probe_line_hit" ||
    value === "outcome_status"
  );
}

function expectationNeedsExpected(operator: string): boolean {
  return (
    operator === "field_equals" ||
    operator === "field_matches_regex" ||
    operator === "numeric_gte" ||
    operator === "numeric_lte" ||
    operator === "contains" ||
    operator === "probe_line_hit" ||
    operator === "outcome_status"
  );
}

function validateExpectationEntries(args: {
  ownerType: "step" | "watcher";
  ownerId: string;
  expectations: PlanStepExpectation[] | undefined;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode:
        | "step_expectations_missing"
        | "step_expectation_invalid"
        | "watcher_expectations_missing"
        | "watcher_expectation_invalid";
      requiredUserAction: string[];
    } {
  const missingReasonCode =
    args.ownerType === "step" ? "step_expectations_missing" : "watcher_expectations_missing";
  const invalidReasonCode =
    args.ownerType === "step" ? "step_expectation_invalid" : "watcher_expectation_invalid";
  const contractPath = args.ownerType === "step" ? "steps[].expect[]" : "watchers[].expect[]";

  if (!Array.isArray(args.expectations) || args.expectations.length === 0) {
    return {
      ok: false,
      reasonCode: missingReasonCode,
      requiredUserAction: [
        `Add deterministic ${contractPath} entries for ${args.ownerType} '${args.ownerId}'.`,
      ],
    };
  }

  for (const raw of args.expectations) {
    const expectation = raw as PlanStepExpectation;
    if (!isRecord(expectation)) {
      return {
        ok: false,
        reasonCode: invalidReasonCode,
        requiredUserAction: [
          `Ensure all expectations for ${args.ownerType} '${args.ownerId}' are objects.`,
        ],
      };
    }

    if (!hasNonBlank(expectation.id)) {
      return {
        ok: false,
        reasonCode: invalidReasonCode,
        requiredUserAction: [
          `Set non-empty expectation id for ${args.ownerType} '${args.ownerId}'.`,
        ],
      };
    }
    if (!hasNonBlank(expectation.actualPath)) {
      return {
        ok: false,
        reasonCode: invalidReasonCode,
        requiredUserAction: [
          `Set non-empty expectation actualPath for ${args.ownerType} '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (!hasNonBlank(expectation.operator) || !isExpectationOperator(expectation.operator)) {
      return {
        ok: false,
        reasonCode: invalidReasonCode,
        requiredUserAction: [
          `Set supported expectation operator for ${args.ownerType} '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (
      expectationNeedsExpected(expectation.operator) &&
      typeof expectation.expected === "undefined"
    ) {
      return {
        ok: false,
        reasonCode: invalidReasonCode,
        requiredUserAction: [
          `Set expectation expected value for ${args.ownerType} '${args.ownerId}' (id='${expectation.id}', operator='${expectation.operator}').`,
        ],
      };
    }
  }

  return { ok: true };
}

export function validateStepExpectations(steps: PlanStep[]):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "step_expectations_missing" | "step_expectation_invalid";
      requiredUserAction: string[];
    } {
  for (const step of steps) {
    const result = validateExpectationEntries({
      ownerType: "step",
      ownerId: step.id,
      expectations: step.expect,
    });
    if (!result.ok) {
      return {
        ok: false,
        reasonCode:
          result.reasonCode === "step_expectations_missing"
            ? "step_expectations_missing"
            : "step_expectation_invalid",
        requiredUserAction: result.requiredUserAction,
      };
    }
  }

  return { ok: true };
}

function isConditionObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isConditionPath(value: string): boolean {
  return /^context\.[A-Za-z0-9_.-]+$/.test(value) || /^step\[\d+\]\.[A-Za-z0-9_.-]+$/.test(value);
}

function validateStepConditionNode(args: { node: PlanStepCondition; currentOrder: number }):
  | { ok: true }
  | {
      ok: false;
      reasonCode:
        | "step_condition_malformed"
        | "step_condition_operator_invalid"
        | "step_condition_forward_reference"
        | "step_condition_path_missing"
        | "step_condition_type_mismatch";
      requiredUserAction: string[];
    } {
  const node = args.node as unknown;
  if (!isConditionObject(node)) {
    return {
      ok: false,
      reasonCode: "step_condition_malformed",
      requiredUserAction: ["Set steps[].when to a condition object."],
    };
  }

  if ("all" in node) {
    const items = (node as { all: unknown }).all;
    if (!Array.isArray(items) || items.length === 0) {
      return {
        ok: false,
        reasonCode: "step_condition_type_mismatch",
        requiredUserAction: ["Set steps[].when.all to a non-empty array."],
      };
    }
    for (const child of items) {
      const childResult = validateStepConditionNode({
        node: child as PlanStepCondition,
        currentOrder: args.currentOrder,
      });
      if (!childResult.ok) return childResult;
    }
    return { ok: true };
  }

  if ("any" in node) {
    const items = (node as { any: unknown }).any;
    if (!Array.isArray(items) || items.length === 0) {
      return {
        ok: false,
        reasonCode: "step_condition_type_mismatch",
        requiredUserAction: ["Set steps[].when.any to a non-empty array."],
      };
    }
    for (const child of items) {
      const childResult = validateStepConditionNode({
        node: child as PlanStepCondition,
        currentOrder: args.currentOrder,
      });
      if (!childResult.ok) return childResult;
    }
    return { ok: true };
  }

  if ("not" in node) {
    const child = (node as { not: unknown }).not;
    if (!isConditionObject(child)) {
      return {
        ok: false,
        reasonCode: "step_condition_type_mismatch",
        requiredUserAction: ["Set steps[].when.not to a condition object."],
      };
    }
    return validateStepConditionNode({
      node: child as PlanStepCondition,
      currentOrder: args.currentOrder,
    });
  }

  const predicate = node as PlanStepConditionPredicate;
  if (!hasNonBlank(predicate.left)) {
    return {
      ok: false,
      reasonCode: "step_condition_path_missing",
      requiredUserAction: ["Set steps[].when.left to a non-empty path."],
    };
  }
  if (!isConditionPath(predicate.left)) {
    return {
      ok: false,
      reasonCode: "step_condition_path_missing",
      requiredUserAction: ["Use steps[].when.left path under context.* or step[n].*."],
    };
  }
  if (predicate.left.startsWith("step[")) {
    const indexEnd = predicate.left.indexOf("]");
    const raw = predicate.left.slice(5, indexEnd);
    const refOrder = Number(raw);
    if (!Number.isFinite(refOrder) || refOrder < 1 || refOrder >= args.currentOrder) {
      return {
        ok: false,
        reasonCode: "step_condition_forward_reference",
        requiredUserAction: [
          "Reference only prior steps in steps[].when (step[n], n < current order).",
        ],
      };
    }
  }
  if (
    predicate.op !== "equals" &&
    predicate.op !== "not_equals" &&
    predicate.op !== "in" &&
    predicate.op !== "exists"
  ) {
    return {
      ok: false,
      reasonCode: "step_condition_operator_invalid",
      requiredUserAction: ["Use steps[].when.op in equals|not_equals|in|exists."],
    };
  }
  if (
    (predicate.op === "equals" || predicate.op === "not_equals" || predicate.op === "in") &&
    typeof predicate.right === "undefined"
  ) {
    return {
      ok: false,
      reasonCode: "step_condition_type_mismatch",
      requiredUserAction: [`Set steps[].when.right for operator '${predicate.op}'.`],
    };
  }
  if (predicate.op === "in" && !Array.isArray(predicate.right)) {
    return {
      ok: false,
      reasonCode: "step_condition_type_mismatch",
      requiredUserAction: ["Set steps[].when.right to an array for operator 'in'."],
    };
  }
  return { ok: true };
}

export function validateStepConditions(steps: PlanStep[]):
  | { ok: true }
  | {
      ok: false;
      reasonCode:
        | "step_condition_malformed"
        | "step_condition_operator_invalid"
        | "step_condition_forward_reference"
        | "step_condition_path_missing"
        | "step_condition_type_mismatch";
      requiredUserAction: string[];
    } {
  for (const step of steps) {
    if (typeof step.when === "undefined") continue;
    const result = validateStepConditionNode({
      node: step.when,
      currentOrder: step.order,
    });
    if (!result.ok) {
      return {
        ok: false,
        reasonCode: result.reasonCode,
        requiredUserAction: [`Fix condition on step '${step.id}'.`, ...result.requiredUserAction],
      };
    }
  }
  return { ok: true };
}

type InvalidTransportPlaceholder = {
  fieldPath: string;
  invalidToken: string;
};

function findInvalidTransportPlaceholder(args: {
  value: unknown;
  fieldPath: string;
}): InvalidTransportPlaceholder | null {
  if (typeof args.value === "string") {
    const normalized = normalizePlaceholderSyntaxInString(args.value);
    if (typeof normalized.invalidToken === "string") {
      return {
        fieldPath: args.fieldPath,
        invalidToken: normalized.invalidToken,
      };
    }
    return null;
  }
  if (Array.isArray(args.value)) {
    for (let index = 0; index < args.value.length; index += 1) {
      const invalid = findInvalidTransportPlaceholder({
        value: args.value[index],
        fieldPath: `${args.fieldPath}[${index}]`,
      });
      if (invalid) return invalid;
    }
    return null;
  }
  if (isRecord(args.value)) {
    for (const [key, entry] of Object.entries(args.value)) {
      const invalid = findInvalidTransportPlaceholder({
        value: entry,
        fieldPath: `${args.fieldPath}.${key}`,
      });
      if (invalid) return invalid;
    }
  }
  return null;
}

export function validateTransportPlaceholderSyntax(steps: PlanStep[]):
  | { ok: true }
  | {
      ok: false;
      reasonCode: "transport_placeholder_syntax_invalid";
      requiredUserAction: string[];
    } {
  for (const step of steps) {
    const invalid = findInvalidTransportPlaceholder({
      value: step.transport,
      fieldPath: "transport",
    });
    if (invalid) {
      return {
        ok: false,
        reasonCode: "transport_placeholder_syntax_invalid",
        requiredUserAction: [
          `Fix malformed placeholder syntax in step '${step.id}' at '${invalid.fieldPath}' (token='${invalid.invalidToken}'). Supported transport placeholder forms are \${key} and {{key}}.`,
        ],
      };
    }
  }
  return { ok: true };
}

export function validateCorrelationPolicy(correlation: PlanCorrelationPolicy | undefined):
  | { ok: true }
  | {
      ok: false;
      reasonCode:
        "correlation_session_missing" | "correlation_window_invalid" | "correlation_key_invalid" | "correlation_expectation_invalid";
      requiredUserAction: string[];
    } {
  if (!correlation || correlation.enabled !== true) return { ok: true };
  if (
    !correlation.key ||
    (correlation.key.type !== "traceId" &&
      correlation.key.type !== "requestId" &&
      correlation.key.type !== "messageId")
  ) {
    return {
      ok: false,
      reasonCode: "correlation_key_invalid",
      requiredUserAction: ["Set correlation.key.type to traceId|requestId|messageId."],
    };
  }
  const expectations = correlation.strictLineExpectations;
  if (expectations) {
    const seen = new Set<string>();
    const lineKeyCounts = new Map<string, number>();
    for (const expectation of expectations) {
      lineKeyCounts.set(
        expectation.strictLineKey,
        (lineKeyCounts.get(expectation.strictLineKey) ?? 0) + 1,
      );
    }
    for (const expectation of expectations) {
      const key = `${expectation.sequenceOrder}:${expectation.strictLineKey}`;
      const strictLineKeyValid = /^[\w.$]+#[\w$<>]+:\d+$/.test(expectation.strictLineKey);
      const selectorValid = ["exact_instance", "any_instance", "all_instances", "aggregate", "quorum"].includes(expectation.selectorPolicy);
      const operatorValid = ["exact", "at_least", "at_most", "range"].includes(expectation.operator);
      const exactCountValid = expectation.operator === "range"
        ? Number.isInteger(expectation.expectedMinHitDelta) && Number.isInteger(expectation.expectedMaxHitDelta)
          && expectation.expectedMinHitDelta !== undefined && expectation.expectedMaxHitDelta !== undefined
          && expectation.expectedMinHitDelta >= 0 && expectation.expectedMaxHitDelta >= expectation.expectedMinHitDelta
        : Number.isInteger(expectation.expectedHitDelta) && expectation.expectedHitDelta !== undefined && expectation.expectedHitDelta >= 0;
      if (!strictLineKeyValid || !Number.isInteger(expectation.sequenceOrder) || expectation.sequenceOrder < 1 || !selectorValid || !operatorValid || !exactCountValid || seen.has(key)) {
        return {
          ok: false,
          reasonCode: "correlation_expectation_invalid",
          requiredUserAction: ["Set unique ordered Strict Line expectations with valid selector policy and bounded count operator."],
        };
      }
      if (expectation.selectorPolicy !== "exact_instance") {
        return {
          ok: false,
          reasonCode: "correlation_expectation_invalid",
          requiredUserAction: ["Use selectorPolicy=exact_instance until frozen multi-instance Probe membership is available."],
        };
      }
      if (lineKeyCounts.get(expectation.strictLineKey)! > 1 && (!Number.isInteger(expectation.stepOrder) || expectation.stepOrder! < 1)) {
        return {
          ok: false,
          reasonCode: "correlation_expectation_invalid",
          requiredUserAction: ["Set stepOrder on every repeated Strict Line expectation to map it to one deterministic plan step."],
        };
      }
      seen.add(key);
    }
  }
  if (
    typeof correlation.window?.maxWindowMs !== "number" ||
    !Number.isFinite(correlation.window.maxWindowMs) ||
    correlation.window.maxWindowMs <= 0
  ) {
    return {
      ok: false,
      reasonCode: "correlation_window_invalid",
      requiredUserAction: ["Set correlation.window.maxWindowMs to a positive number."],
    };
  }
  if (
    correlation.crossPlan === true &&
    (typeof correlation.correlationSessionId !== "string" ||
      correlation.correlationSessionId.trim() === "")
  ) {
    return {
      ok: false,
      reasonCode: "correlation_session_missing",
      requiredUserAction: [
        "Set non-empty correlation.correlationSessionId when correlation.crossPlan=true.",
      ],
    };
  }
  if (
    typeof correlation.correlationSessionId !== "undefined" &&
    (typeof correlation.correlationSessionId !== "string" ||
      correlation.correlationSessionId.trim() === "")
  ) {
    return {
      ok: false,
      reasonCode: "correlation_session_missing",
      requiredUserAction: [
        "Set non-empty correlation.correlationSessionId when correlation is session-scoped.",
      ],
    };
  }
  return { ok: true };
}

export function classifyPrerequisites(args: {
  prerequisites: PlanPrerequisite[];
  providedContext: Record<string, unknown>;
  discoveryPolicy: "disabled" | "allow_discoverable_prerequisites";
}):
  | {
      type: "ok";
      resolution: PrerequisiteResolution[];
      missing: string[];
      discoverablePending: string[];
    }
  | {
      type: "blocked_invalid";
      reasonCode:
        | "invalid_discoverable_prerequisite"
        | "discoverable_prerequisite_policy_disabled"
        | "secret_default_forbidden";
      requiredUserAction: string[];
      resolution: PrerequisiteResolution[];
    } {
  const resolution: PrerequisiteResolution[] = [];
  const missing: string[] = [];
  const discoverablePending: string[] = [];

  for (const prerequisite of args.prerequisites) {
    if (prerequisite.secret && typeof prerequisite.default !== "undefined") {
      return {
        type: "blocked_invalid",
        reasonCode: "secret_default_forbidden",
        requiredUserAction: [
          `Remove default value from secret prerequisite '${prerequisite.key}'.`,
        ],
        resolution,
      };
    }

    if (
      prerequisite.provisioning === "discoverable" &&
      (typeof prerequisite.discoverySource === "undefined" || prerequisite.discoverySource === null)
    ) {
      return {
        type: "blocked_invalid",
        reasonCode: "invalid_discoverable_prerequisite",
        requiredUserAction: [
          `Set discoverySource for discoverable prerequisite '${prerequisite.key}'.`,
        ],
        resolution,
      };
    }

    const provided = args.providedContext[prerequisite.key];
    if (hasNonBlank(provided)) {
      resolution.push({
        key: prerequisite.key,
        required: prerequisite.required,
        secret: prerequisite.secret,
        provisioning: prerequisite.provisioning,
        status: "provided",
      });
      continue;
    }

    if (typeof prerequisite.default !== "undefined") {
      resolution.push({
        key: prerequisite.key,
        required: prerequisite.required,
        secret: prerequisite.secret,
        provisioning: prerequisite.provisioning,
        status: "default_applied",
      });
      continue;
    }

    if (!prerequisite.required) {
      continue;
    }

    if (prerequisite.provisioning === "discoverable") {
      if (args.discoveryPolicy !== "allow_discoverable_prerequisites") {
        return {
          type: "blocked_invalid",
          reasonCode: "discoverable_prerequisite_policy_disabled",
          requiredUserAction: [
            "Set metadata.execution.discoveryPolicy to allow_discoverable_prerequisites.",
          ],
          resolution,
        };
      }
      discoverablePending.push(prerequisite.key);
      resolution.push({
        key: prerequisite.key,
        required: prerequisite.required,
        secret: prerequisite.secret,
        provisioning: prerequisite.provisioning,
        status: "discoverable_pending",
      });
      continue;
    }

    missing.push(prerequisite.key);
    resolution.push({
      key: prerequisite.key,
      required: prerequisite.required,
      secret: prerequisite.secret,
      provisioning: prerequisite.provisioning,
      status: "needs_user_input",
    });
  }

  return {
    type: "ok",
    resolution,
    missing,
    discoverablePending,
  };
}
