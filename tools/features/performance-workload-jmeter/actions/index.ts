import {
  runJmeterGeneratedHttpWorkload,
  type RunJmeterGeneratedHttpWorkloadArgs,
} from "../runners/jmeter_cli_runner";

export type PerformanceWorkloadJmeterActionMap = Readonly<Record<"execute", typeof runJmeterGeneratedHttpWorkload>>;
export type PerformanceWorkloadJmeterActionRequest = {
  action: "execute";
  input: RunJmeterGeneratedHttpWorkloadArgs;
};

export function dispatchPerformanceWorkloadJmeterAction(
  request: PerformanceWorkloadJmeterActionRequest,
): ReturnType<typeof runJmeterGeneratedHttpWorkload> {
  switch (request.action) {
    case "execute":
      return runJmeterGeneratedHttpWorkload(request.input);
  }
}
