import { exportExecutionProfileAction } from "./export_execution_profile.action";

export type ExecutionProfileExportActionMap = Readonly<Record<"export", typeof exportExecutionProfileAction>>;

export function dispatchExecutionProfileExportAction(
  input: Parameters<typeof exportExecutionProfileAction>[0],
) {
  switch ("export") {
    case "export":
      return exportExecutionProfileAction(input);
  }
}
