import { ExecutionOrchestrationInputSchema } from "@tools-contracts/execution-orchestration";
import { EXECUTION_ORCHESTRATION_TOOL_CONTRACT } from "@tools-contracts/execution-orchestration";

export const EXECUTION_ORCHESTRATION_TOOL = {
  ...EXECUTION_ORCHESTRATION_TOOL_CONTRACT,
  inputSchema: ExecutionOrchestrationInputSchema,
} as const;

