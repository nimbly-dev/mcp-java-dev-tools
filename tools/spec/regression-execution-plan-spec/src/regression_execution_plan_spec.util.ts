import type {
  BuildPreflightArgs,
  PlanCorrelationPolicy,
  PlanStepCondition,
  PlanStepConditionPredicate,
  PlanStepExpectation,
  PlanPrerequisite,
  PrerequisiteResolution,
  PlanStep,
  PreflightResult,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

export type {
  BuildPreflightArgs,
  PlanContract,
  PlanMetadata,
  PlanPrerequisite,
  PlanStep,
  PlanTarget,
  PreflightResult,
  PreflightStatus,
  RegressionExecutionIntent,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

function hasDuplicate(values: number[]): boolean {
  return new Set(values).size !== values.length;
}

function isStrictProbeKey(value: string): boolean {
  return /^[\w.$]+#[\w$]+:\d+$/.test(value.trim());
}

function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function emptyPreflightDetails() {
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

function validateStepExpectations(
  steps: PlanStep[],
):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "step_expectations_missing" | "step_expectation_invalid";
      requiredUserAction: string[];
    } {
  for (const step of steps) {
    if (!Array.isArray(step.expect) || step.expect.length === 0) {
      return {
        ok: false,
        reasonCode: "step_expectations_missing",
        requiredUserAction: [
          `Add deterministic steps[].expect[] entries for step '${step.id}'.`,
        ],
      };
    }

    for (const raw of step.expect) {
      const expectation = raw as PlanStepExpectation;
      if (!isRecord(expectation)) {
        return {
          ok: false,
          reasonCode: "step_expectation_invalid",
          requiredUserAction: [`Ensure all expectations for step '${step.id}' are objects.`],
        };
      }

      if (!hasNonBlank(expectation.id)) {
        return {
          ok: false,
          reasonCode: "step_expectation_invalid",
          requiredUserAction: [`Set non-empty expectation id for step '${step.id}'.`],
        };
      }
      if (!hasNonBlank(expectation.actualPath)) {
        return {
          ok: false,
          reasonCode: "step_expectation_invalid",
          requiredUserAction: [
            `Set non-empty expectation actualPath for step '${step.id}' (id='${expectation.id}').`,
          ],
        };
      }
      if (!hasNonBlank(expectation.operator) || !isExpectationOperator(expectation.operator)) {
        return {
          ok: false,
          reasonCode: "step_expectation_invalid",
          requiredUserAction: [
            `Set supported expectation operator for step '${step.id}' (id='${expectation.id}').`,
          ],
        };
      }
      if (
        expectationNeedsExpected(expectation.operator) &&
        typeof expectation.expected === "undefined"
      ) {
        return {
          ok: false,
          reasonCode: "step_expectation_invalid",
          requiredUserAction: [
            `Set expectation expected value for step '${step.id}' (id='${expectation.id}', operator='${expectation.operator}').`,
          ],
        };
      }
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

function validateStepConditionNode(args: {
  node: PlanStepCondition;
  currentOrder: number;
}):
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
        requiredUserAction: ["Reference only prior steps in steps[].when (step[n], n < current order)."],
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
  if ((predicate.op === "equals" || predicate.op === "not_equals" || predicate.op === "in") && typeof predicate.right === "undefined") {
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

function validateStepConditions(
  steps: PlanStep[],
):
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

function validateCorrelationPolicy(
  correlation: PlanCorrelationPolicy | undefined,
):
  | { ok: true }
  | {
      ok: false;
      reasonCode: "correlation_session_missing" | "correlation_window_invalid" | "correlation_key_invalid";
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
  if (correlation.crossPlan === true) {
    const sessionId = correlation.correlationSessionId;
    if (typeof sessionId !== "string" || sessionId.trim() === "") {
      return {
        ok: false,
        reasonCode: "correlation_session_missing",
        requiredUserAction: ["Set non-empty correlation.correlationSessionId when crossPlan=true."],
      };
    }
  }
  return { ok: true };
}

function classifyPrerequisites(args: {
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

export function buildReplayPreflight(args: BuildPreflightArgs): PreflightResult {
  const { metadata, contract, providedContext, targetCandidateCount } = args;
  if (args.projectContext?.status === "blocked" && args.projectContext.reasonCode) {
    const isNeedsUserInput =
      args.projectContext.reasonCode === "env_key_missing" ||
      args.projectContext.reasonCode === "external_healthcheck_failed" ||
      args.projectContext.reasonCode === "runtime_context_unknown";
    const nextAction =
      typeof args.projectContext.nextAction === "string" && args.projectContext.nextAction.trim().length > 0
        ? args.projectContext.nextAction
        : (args.projectContext.requiredUserAction?.[0] ?? "Provide required project context input.");
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
  const stepConditionValidation = validateStepConditions(contract.steps);
  if (!stepConditionValidation.ok) {
    return {
      status: "blocked_invalid",
      reasonCode: stepConditionValidation.reasonCode,
      ...emptyPreflightDetails(),
      requiredUserAction: stepConditionValidation.requiredUserAction,
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
  return resolved;
}

function deepResolveValue(value: unknown, context: Record<string, unknown>): unknown {
  if (typeof value === "string") {
    return value.replace(/\$\{([^}]+)\}/g, (_match, key) => {
      const resolved = context[key];
      if (typeof resolved === "undefined" || resolved === null) {
        throw new Error(`missing_context:${key}`);
      }
      return String(resolved);
    });
  }
  if (Array.isArray(value)) {
    return value.map((item) => deepResolveValue(item, context));
  }
  if (value && typeof value === "object") {
    const output: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      output[k] = deepResolveValue(v, context);
    }
    return output;
  }
  return value;
}

export function resolveStepTransport(step: PlanStep, context: Record<string, unknown>): Record<string, unknown> {
  return deepResolveValue(step.transport, context) as Record<string, unknown>;
}

function readByPath(input: Record<string, unknown>, path: string): unknown {
  const segments = path.split(".");
  let cursor: unknown = input;
  for (const segment of segments) {
    if (!cursor || typeof cursor !== "object") return undefined;
    cursor = (cursor as Record<string, unknown>)[segment];
  }
  return cursor;
}

export function applyStepExtract(
  output: Record<string, unknown>,
  extract: Array<{ from: string; as: string }> | undefined,
  context: Record<string, unknown>,
): Record<string, unknown> {
  if (!extract?.length) return context;
  const next = { ...context };
  for (const mapping of extract) {
    const value = readByPath(output, mapping.from);
    if (typeof value !== "undefined") next[mapping.as] = value;
  }
  return next;
}

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

