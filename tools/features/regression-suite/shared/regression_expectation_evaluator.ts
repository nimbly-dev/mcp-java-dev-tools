import { isDeepStrictEqual } from "node:util";

import type {
  PlanStepExpectation,
  PlanStepExpectationOperator,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type { RegressionRunStatus } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { readValueByPath } from "@tools-core/object_path_read";

export type StepExecutionOutcomeStatus =
  | "pass"
  | "fail_assertion"
  | "fail_http"
  | "blocked_dependency"
  | "blocked_runtime"
  | "skipped_condition_false";

export type AssertionEvaluationStatus = "pass" | "fail" | "blocked_invalid";

export type AssertionEvaluationReasonCode =
  | "ok"
  | "actual_path_missing"
  | "operator_unknown"
  | "operator_expected_missing"
  | "type_mismatch"
  | "regex_invalid"
  | "predicate_false";

export type StepAssertionEvaluation = {
  id: string;
  operator: PlanStepExpectationOperator;
  actualPath: string;
  required: boolean;
  status: AssertionEvaluationStatus;
  reasonCode: AssertionEvaluationReasonCode;
  actual?: unknown;
  expected?: unknown;
  message?: string;
};

export type EvaluateStepExpectationsResult = {
  assertions: StepAssertionEvaluation[];
  status: StepExecutionOutcomeStatus;
};

export type DeriveRunStatusArgs = {
  stepOutcomes: Array<{
    status: StepExecutionOutcomeStatus;
    required?: boolean;
  }>;
  hardRuntimeBlocker: boolean;
};

function withOptionalMessage<T extends Record<string, unknown>>(base: T, message: string | undefined): T {
  if (typeof message === "string" && message.length > 0) {
    return { ...base, message };
  }
  return base;
}

type ComparableNumeric = {
  negative: boolean;
  integer: string;
  fraction: string;
};

function normalizeComparableNumeric(value: unknown): ComparableNumeric | null {
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      return null;
    }
    return normalizeComparableNumeric(String(value));
  }
  if (typeof value !== "string") {
    return null;
  }

  const trimmed = value.trim();
  const match = /^([+-])?(\d+)(?:\.(\d+))?$/.exec(trimmed);
  if (!match) {
    return null;
  }

  const sign = match[1] ?? "";
  const integerRaw = match[2] ?? "0";
  const fractionRaw = match[3] ?? "";
  const integer = integerRaw.replace(/^0+(?=\d)/, "");
  const fraction = fractionRaw.replace(/0+$/, "");
  const isZero = /^0+$/.test(integer) && fraction.length === 0;

  return {
    negative: sign === "-" && !isZero,
    integer,
    fraction,
  };
}

function compareComparableNumeric(left: ComparableNumeric, right: ComparableNumeric): number {
  if (left.negative !== right.negative) {
    return left.negative ? -1 : 1;
  }

  const direction = left.negative ? -1 : 1;
  if (left.integer.length !== right.integer.length) {
    return left.integer.length > right.integer.length ? direction : -direction;
  }
  if (left.integer !== right.integer) {
    return left.integer > right.integer ? direction : -direction;
  }

  const fractionLength = Math.max(left.fraction.length, right.fraction.length);
  const leftFraction = left.fraction.padEnd(fractionLength, "0");
  const rightFraction = right.fraction.padEnd(fractionLength, "0");
  if (leftFraction === rightFraction) {
    return 0;
  }
  return leftFraction > rightFraction ? direction : -direction;
}

function evaluatePredicate(args: {
  operator: PlanStepExpectationOperator;
  actual: unknown;
  expected: unknown;
}):
  | {
      status: "pass" | "fail";
      reasonCode: "ok" | "predicate_false";
    }
  | {
      status: "blocked_invalid";
      reasonCode: "type_mismatch" | "regex_invalid";
    } {
  const { operator, actual, expected } = args;
  if (operator === "field_equals") {
    return {
      status: isDeepStrictEqual(actual, expected) ? "pass" : "fail",
      reasonCode: isDeepStrictEqual(actual, expected) ? "ok" : "predicate_false",
    };
  }
  if (operator === "field_exists") {
    return {
      status: typeof actual !== "undefined" ? "pass" : "fail",
      reasonCode: typeof actual !== "undefined" ? "ok" : "predicate_false",
    };
  }
  if (operator === "field_matches_regex") {
    if (typeof actual !== "string" || typeof expected !== "string") {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    try {
      const regex = new RegExp(expected);
      return {
        status: regex.test(actual) ? "pass" : "fail",
        reasonCode: regex.test(actual) ? "ok" : "predicate_false",
      };
    } catch {
      return { status: "blocked_invalid", reasonCode: "regex_invalid" };
    }
  }
  if (operator === "numeric_gte") {
    const actualNumeric = normalizeComparableNumeric(actual);
    const expectedNumeric = normalizeComparableNumeric(expected);
    if (!actualNumeric || !expectedNumeric) {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: compareComparableNumeric(actualNumeric, expectedNumeric) >= 0 ? "pass" : "fail",
      reasonCode: compareComparableNumeric(actualNumeric, expectedNumeric) >= 0 ? "ok" : "predicate_false",
    };
  }
  if (operator === "numeric_lte") {
    const actualNumeric = normalizeComparableNumeric(actual);
    const expectedNumeric = normalizeComparableNumeric(expected);
    if (!actualNumeric || !expectedNumeric) {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: compareComparableNumeric(actualNumeric, expectedNumeric) <= 0 ? "pass" : "fail",
      reasonCode: compareComparableNumeric(actualNumeric, expectedNumeric) <= 0 ? "ok" : "predicate_false",
    };
  }
  if (operator === "contains") {
    if (typeof actual === "string" && typeof expected === "string") {
      return {
        status: actual.includes(expected) ? "pass" : "fail",
        reasonCode: actual.includes(expected) ? "ok" : "predicate_false",
      };
    }
    if (Array.isArray(actual)) {
      const found = actual.some((item) => isDeepStrictEqual(item, expected));
      return {
        status: found ? "pass" : "fail",
        reasonCode: found ? "ok" : "predicate_false",
      };
    }
    return { status: "blocked_invalid", reasonCode: "type_mismatch" };
  }
  if (operator === "probe_line_hit") {
    if (typeof actual !== "boolean" || typeof expected !== "boolean") {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: actual === expected ? "pass" : "fail",
      reasonCode: actual === expected ? "ok" : "predicate_false",
    };
  }
  if (operator === "outcome_status") {
    if (typeof actual !== "string" || typeof expected !== "string") {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: actual === expected ? "pass" : "fail",
      reasonCode: actual === expected ? "ok" : "predicate_false",
    };
  }
  return { status: "blocked_invalid", reasonCode: "type_mismatch" };
}

function operatorNeedsExpected(operator: PlanStepExpectationOperator): boolean {
  return operator !== "field_exists";
}

function resolveActualPath(stepResult: Record<string, unknown>, actualPath: string): unknown {
  const direct = readValueByPath(stepResult, actualPath);
  if (typeof direct !== "undefined") {
    return direct;
  }

  const aliasPaths = new Set<string>();
  if (actualPath === "statusCode") {
    aliasPaths.add("response.statusCode");
  }
  if (actualPath === "outcome") {
    aliasPaths.add("status");
  }
  if (actualPath === "transport.status_code") {
    aliasPaths.add("response.statusCode");
  }
  if (actualPath.startsWith("runtime.probe.")) {
    aliasPaths.add(`probe.${actualPath.slice("runtime.probe.".length)}`);
  }

  for (const aliasPath of aliasPaths) {
    const aliased = readValueByPath(stepResult, aliasPath);
    if (typeof aliased !== "undefined") {
      return aliased;
    }
  }
  return undefined;
}

export function evaluateStepExpectations(args: {
  stepResult: Record<string, unknown>;
  expectations: PlanStepExpectation[];
  transportFailure: boolean;
  dependencyBlocked: boolean;
}): EvaluateStepExpectationsResult {
  const assertions: StepAssertionEvaluation[] = [];

  for (const expectation of args.expectations) {
    const required = expectation.required !== false;
    const actual = resolveActualPath(args.stepResult, expectation.actualPath);
    if (typeof actual === "undefined") {
      assertions.push(
        withOptionalMessage({
        id: expectation.id,
        operator: expectation.operator,
        actualPath: expectation.actualPath,
        required,
        status: "blocked_invalid",
        reasonCode: "actual_path_missing",
        expected: expectation.expected,
        }, expectation.message),
      );
      continue;
    }

    if (operatorNeedsExpected(expectation.operator) && typeof expectation.expected === "undefined") {
      assertions.push(
        withOptionalMessage({
        id: expectation.id,
        operator: expectation.operator,
        actualPath: expectation.actualPath,
        required,
        status: "blocked_invalid",
        reasonCode: "operator_expected_missing",
        actual,
        }, expectation.message),
      );
      continue;
    }

    const predicate = evaluatePredicate({
      operator: expectation.operator,
      actual,
      expected: expectation.expected,
    });

    assertions.push(
      withOptionalMessage({
      id: expectation.id,
      operator: expectation.operator,
      actualPath: expectation.actualPath,
      required,
      status: predicate.status,
      reasonCode: predicate.reasonCode,
      actual,
      expected: expectation.expected,
      }, expectation.message),
    );
  }

  if (args.dependencyBlocked) {
    return { assertions, status: "blocked_dependency" };
  }
  if (assertions.some((entry) => entry.status === "blocked_invalid")) {
    return { assertions, status: "blocked_runtime" };
  }
  if (assertions.some((entry) => entry.status === "fail")) {
    return { assertions, status: "fail_assertion" };
  }
  if (args.transportFailure) {
    // Step status follows the authored expectations. The required flag is
    // applied later when deriving whether a non-pass step should fail the
    // overall run.
    return {
      assertions,
      status: assertions.length > 0 ? "pass" : "fail_http",
    };
  }
  return { assertions, status: "pass" };
}

export function deriveRunStatusFromStepOutcomes(args: DeriveRunStatusArgs): RegressionRunStatus {
  if (args.hardRuntimeBlocker) return "blocked";
  const required = args.stepOutcomes.filter((entry) => entry.required !== false);
  if (required.some((entry) => entry.status === "blocked_runtime")) return "blocked";
  if (
    required.some(
      (entry) =>
        entry.status === "fail_assertion" ||
        entry.status === "fail_http" ||
        entry.status === "blocked_dependency",
    )
  ) {
    return "fail";
  }
  return "pass";
}
