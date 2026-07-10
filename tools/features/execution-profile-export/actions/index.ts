import { executionProfileExportDomain } from "../domain";

export type ExecutionProfileExportActionMap = Readonly<Record<"export", typeof executionProfileExportDomain>>;

export const dispatchExecutionProfileExportAction = executionProfileExportDomain;
