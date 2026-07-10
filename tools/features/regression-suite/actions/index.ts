import {
  executeRegressionPlanWorkflow,
  type ExecuteRegressionPlanWorkflowArgs,
} from "./execute_regression_plan.action";
import {
  executeRegressionRuntimeSuite,
  type ExecuteRegressionRuntimeSuiteArgs,
} from "./execute_regression_runtime_suite.action";

export type RegressionSuiteAction = "execute_plan" | "execute_runtime_suite";
export type RegressionSuiteActionMap = Readonly<{
  execute_plan: typeof executeRegressionPlanWorkflow;
  execute_runtime_suite: typeof executeRegressionRuntimeSuite;
}>;
export type RegressionSuiteActionRequest =
  | { action: "execute_plan"; input: ExecuteRegressionPlanWorkflowArgs }
  | { action: "execute_runtime_suite"; input: ExecuteRegressionRuntimeSuiteArgs };

export function dispatchRegressionSuiteAction(
  request: { action: "execute_plan"; input: ExecuteRegressionPlanWorkflowArgs },
): ReturnType<typeof executeRegressionPlanWorkflow>;
export function dispatchRegressionSuiteAction(
  request: { action: "execute_runtime_suite"; input: ExecuteRegressionRuntimeSuiteArgs },
): ReturnType<typeof executeRegressionRuntimeSuite>;
export function dispatchRegressionSuiteAction(request: RegressionSuiteActionRequest) {
  switch (request.action) {
    case "execute_plan":
      return executeRegressionPlanWorkflow(request.input);
    case "execute_runtime_suite":
      return executeRegressionRuntimeSuite(request.input);
  }
}
