import type { PlanContract } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type {
  RegressionRunExecutionResult,
  RegressionRunStepResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";

export type LoadedPlanRunArtifacts = {
  contract: PlanContract;
  contextResolved: Record<string, unknown>;
  executionResult: RegressionRunExecutionResult;
};

export type ShStepRenderArgs = {
  planName: string;
  step: PlanContract["steps"][number];
  stepResult?: RegressionRunStepResult;
  contextResolved: Record<string, unknown>;
};

export type ShTransportRenderResult = {
  handled: boolean;
  lines: string[];
};
