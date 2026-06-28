import { isDeepStrictEqual } from "node:util";

import type {
  PlanStepExpectation,
  PlanStepExpectationOperator,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type { RegressionRunStatus } from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { readValueByPath } from "@tools-regression-execution-plan-spec/suite_path_reader.util";

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
    if (typeof actual !== "number" || typeof expected !== "number") {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: actual >= expected ? "pass" : "fail",
      reasonCode: actual >= expected ? "ok" : "predicate_false",
    };
  }
  if (operator === "numeric_lte") {
    if (typeof actual !== "number" || typeof expected !== "number") {
      return { status: "blocked_invalid", reasonCode: "type_mismatch" };
    }
    return {
      status: actual <= expected ? "pass" : "fail",
      reasonCode: actual <= expected ? "ok" : "predicate_false",
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

export function evaluateStepExpectations(args: {
  stepResult: Record<string, unknown>;
  expectations: PlanStepExpectation[];
  transportFailure: boolean;
  dependencyBlocked: boolean;
}): EvaluateStepExpectationsResult {
  const assertions: StepAssertionEvaluation[] = [];

  for (const expectation of args.expectations) {
    const required = expectation.required !== false;
    const actual = readValueByPath(args.stepResult, expectation.actualPath);
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
