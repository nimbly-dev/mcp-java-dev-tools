import { executePerformanceRuntimeSuite } from "./execute_performance_runtime_suite.action";
import type { ExecutePerformanceRuntimeSuiteArgs } from "../models/performance_suite.model";

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
