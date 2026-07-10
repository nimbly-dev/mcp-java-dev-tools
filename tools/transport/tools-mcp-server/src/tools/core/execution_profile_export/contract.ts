import { ExecutionProfileExportInputSchema } from "@tools-contracts/execution-profile-export";
import { EXECUTION_PROFILE_EXPORT_TOOL_CONTRACT } from "@tools-contracts/execution-profile-export";

export const EXECUTION_PROFILE_EXPORT_TOOL = {
  ...EXECUTION_PROFILE_EXPORT_TOOL_CONTRACT,
  inputSchema: ExecutionProfileExportInputSchema,
} as const;
