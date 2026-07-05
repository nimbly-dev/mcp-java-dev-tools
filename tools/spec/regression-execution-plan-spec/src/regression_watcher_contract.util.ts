import type {
  PlanStep,
  PlanStepExpectation,
  PlanWatcher,
  PlanWatcherWaitPolicy,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function asPositiveInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
}

function isWatcherResponseBodyFormat(value: unknown): boolean {
  return value === "auto" || value === "json" || value === "text";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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

function validateWatcherExpectationEntries(args: {
  ownerId: string;
  expectations: PlanStepExpectation[] | undefined;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "watcher_expectations_missing" | "watcher_expectation_invalid";
      requiredUserAction: string[];
    } {
  const contractPath = "watchers[].expect[]";
  if (!Array.isArray(args.expectations) || args.expectations.length === 0) {
    return {
      ok: false,
      reasonCode: "watcher_expectations_missing",
      requiredUserAction: [`Add deterministic ${contractPath} entries for watcher '${args.ownerId}'.`],
    };
  }

  for (const raw of args.expectations) {
    const expectation = raw as PlanStepExpectation;
    if (!isRecord(expectation)) {
      return {
        ok: false,
        reasonCode: "watcher_expectation_invalid",
        requiredUserAction: [`Ensure all expectations for watcher '${args.ownerId}' are objects.`],
      };
    }
    if (!hasNonBlank(expectation.id)) {
      return {
        ok: false,
        reasonCode: "watcher_expectation_invalid",
        requiredUserAction: [`Set non-empty expectation id for watcher '${args.ownerId}'.`],
      };
    }
    if (!hasNonBlank(expectation.actualPath)) {
      return {
        ok: false,
        reasonCode: "watcher_expectation_invalid",
        requiredUserAction: [
          `Set non-empty expectation actualPath for watcher '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (!hasNonBlank(expectation.operator) || !isExpectationOperator(expectation.operator)) {
      return {
        ok: false,
        reasonCode: "watcher_expectation_invalid",
        requiredUserAction: [
          `Set supported expectation operator for watcher '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (expectationNeedsExpected(expectation.operator) && typeof expectation.expected === "undefined") {
      return {
        ok: false,
        reasonCode: "watcher_expectation_invalid",
        requiredUserAction: [
          `Set expectation expected value for watcher '${args.ownerId}' (id='${expectation.id}', operator='${expectation.operator}').`,
        ],
      };
    }
  }

  return { ok: true };
}

export type ResolvedWatcherWaitPolicy = {
  timeoutMs?: number;
  timeoutSource: "watcher_override" | "project_default" | "unresolved";
  retryMax?: number;
  retrySource: "watcher_override" | "project_default" | "unresolved";
};

export function resolveWatcherWaitPolicy(args: {
  watcher: Pick<PlanWatcher, "waitPolicy">;
  providedContext?: Record<string, unknown>;
}): ResolvedWatcherWaitPolicy {
  const timeoutOverride = asPositiveInteger(args.watcher.waitPolicy?.timeoutMs);
  const retryOverride = asPositiveInteger(args.watcher.waitPolicy?.retryMax);
  const inheritedTimeoutMs = asPositiveInteger(args.providedContext?.["runtime.requestTimeoutMs"]);
  const inheritedRetryMax = asPositiveInteger(args.providedContext?.["runtime.retryMax"]);

  return {
    ...(typeof timeoutOverride === "number"
      ? { timeoutMs: timeoutOverride, timeoutSource: "watcher_override" as const }
      : typeof inheritedTimeoutMs === "number"
        ? { timeoutMs: inheritedTimeoutMs, timeoutSource: "project_default" as const }
        : { timeoutSource: "unresolved" as const }),
    ...(typeof retryOverride === "number"
      ? { retryMax: retryOverride, retrySource: "watcher_override" as const }
      : typeof inheritedRetryMax === "number"
        ? { retryMax: inheritedRetryMax, retrySource: "project_default" as const }
        : { retrySource: "unresolved" as const }),
  };
}

export function validateWatchers(
  watchers: PlanWatcher[] | undefined,
  steps: PlanStep[],
):
  | { ok: true }
  | {
      ok: false;
      reasonCode:
        | "watcher_id_invalid"
        | "watcher_dependency_invalid"
        | "watcher_provider_invalid"
        | "watcher_wait_policy_invalid"
        | "watcher_expectations_missing"
        | "watcher_expectation_invalid";
      requiredUserAction: string[];
    } {
  if (typeof watchers === "undefined") return { ok: true };
  if (!Array.isArray(watchers)) {
    return {
      ok: false,
      reasonCode: "watcher_id_invalid",
      requiredUserAction: ["Set contract.watchers to an array of watcher definitions."],
    };
  }

  const validStepOrders = new Set(steps.map((step) => step.order));
  const watcherIds = new Set<string>();
  for (const rawWatcher of watchers) {
    const watcher = rawWatcher as PlanWatcher;
    if (!isRecord(watcher) || !hasNonBlank(watcher.id)) {
      return {
        ok: false,
        reasonCode: "watcher_id_invalid",
        requiredUserAction: ["Set non-empty watcher id values in contract.watchers[].id."],
      };
    }

    const watcherId = watcher.id.trim();
    if (watcherIds.has(watcherId)) {
      return {
        ok: false,
        reasonCode: "watcher_id_invalid",
        requiredUserAction: [`Ensure watcher id '${watcherId}' is unique within contract.watchers[].`],
      };
    }
    watcherIds.add(watcherId);

    const dependency = watcher.dependency;
    if (!isRecord(dependency) || !asPositiveInteger(dependency.stepOrder) || !validStepOrders.has(dependency.stepOrder)) {
      return {
        ok: false,
        reasonCode: "watcher_dependency_invalid",
        requiredUserAction: [
          `Set watcher '${watcherId}' dependency.stepOrder to an existing prior step order from contract.steps[].order.`,
        ],
      };
    }

    const provider = watcher.provider;
    const hasTransport = isRecord(provider?.transport);
    const hasConfig = isRecord(provider?.config);
    if (!isRecord(provider) || !hasNonBlank(provider.type) || (!hasTransport && !hasConfig)) {
      return {
        ok: false,
        reasonCode: "watcher_provider_invalid",
        requiredUserAction: [
          `Set watcher '${watcherId}' provider.type plus at least one provider.transport or provider.config object.`,
        ],
      };
    }
    if (
      typeof provider.transport !== "undefined" && !isRecord(provider.transport) ||
      typeof provider.config !== "undefined" && !isRecord(provider.config)
    ) {
      return {
        ok: false,
        reasonCode: "watcher_provider_invalid",
        requiredUserAction: [
          `Set watcher '${watcherId}' provider.transport and provider.config to objects when present.`,
        ],
      };
    }
    if (provider.type.trim() === "http" && !hasTransport) {
      return {
        ok: false,
        reasonCode: "watcher_provider_invalid",
        requiredUserAction: [
          `Set watcher '${watcherId}' provider.transport for provider.type='http'.`,
        ],
      };
    }
    if (hasConfig) {
      const providerConfig = provider.config as Record<string, unknown>;
      const responseConfig = typeof providerConfig.response === "undefined"
        ? undefined
        : isRecord(providerConfig.response)
          ? providerConfig.response
          : null;
      if (responseConfig === null) {
        return {
          ok: false,
          reasonCode: "watcher_provider_invalid",
          requiredUserAction: [
            `Set watcher '${watcherId}' provider.config.response to an object when present.`,
          ],
        };
      }
      if (
        responseConfig &&
        typeof responseConfig.bodyFormat !== "undefined" &&
        !isWatcherResponseBodyFormat(responseConfig.bodyFormat)
      ) {
        return {
          ok: false,
          reasonCode: "watcher_provider_invalid",
          requiredUserAction: [
            `Set watcher '${watcherId}' provider.config.response.bodyFormat to auto|json|text.`,
          ],
        };
      }
    }

    if (typeof watcher.waitPolicy !== "undefined") {
      const waitPolicy = watcher.waitPolicy as PlanWatcherWaitPolicy;
      if (!isRecord(waitPolicy)) {
        return {
          ok: false,
          reasonCode: "watcher_wait_policy_invalid",
          requiredUserAction: [`Set watcher '${watcherId}' waitPolicy to an object when overriding wait defaults.`],
        };
      }
      const waitPolicyKeys = Object.keys(waitPolicy);
      if (
        waitPolicyKeys.length === 0 ||
        waitPolicyKeys.some((key) => key !== "timeoutMs" && key !== "retryMax") ||
        (typeof waitPolicy.timeoutMs !== "undefined" && typeof asPositiveInteger(waitPolicy.timeoutMs) === "undefined") ||
        (typeof waitPolicy.retryMax !== "undefined" && typeof asPositiveInteger(waitPolicy.retryMax) === "undefined")
      ) {
        return {
          ok: false,
          reasonCode: "watcher_wait_policy_invalid",
          requiredUserAction: [
            `Set watcher '${watcherId}' waitPolicy using positive integer timeoutMs and/or retryMax overrides only.`,
          ],
        };
      }
    }

    const expectationValidation = validateWatcherExpectationEntries({
      ownerId: watcherId,
      expectations: watcher.expect,
    });
    if (!expectationValidation.ok) {
      return expectationValidation;
    }
  }

  return { ok: true };
}
