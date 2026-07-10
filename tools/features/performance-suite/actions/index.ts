import { executePerformanceRuntimeSuite, type ExecutePerformanceRuntimeSuiteArgs } from "./execute_performance_runtime_suite.action";

export type PerformanceSuiteActionMap = Readonly<Record<"execute", typeof executePerformanceRuntimeSuite>>;
export type PerformanceSuiteActionRequest = {
  action: "execute";
  input: ExecutePerformanceRuntimeSuiteArgs;
};

export function dispatchPerformanceSuiteAction(
  request: PerformanceSuiteActionRequest,
): ReturnType<typeof executePerformanceRuntimeSuite> {
  switch (request.action) {
    case "execute":
      return executePerformanceRuntimeSuite(request.input);
  }
}
