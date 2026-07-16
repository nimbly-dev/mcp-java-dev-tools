import type { PlanStepExpectationOperator } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";

export type StepExecutionOutcomeStatus =
  | "pass"
  | "fail_assertion"
  | "fail_http"
  | "blocked_dependency"
  | "blocked_runtime"
  | "skipped_condition_false";
export type AssertionEvaluationStatus = "pass" | "fail" | "blocked_invalid" | "skipped_optional";
export type AssertionEvaluationReasonCode =
  | "ok"
  | "actual_path_missing"
  | "optional_actual_path_missing"
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
  stepOutcomes: Array<{ status: StepExecutionOutcomeStatus; required?: boolean }>;
  hardRuntimeBlocker: boolean;
};
