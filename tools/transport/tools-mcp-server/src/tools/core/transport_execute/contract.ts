import { TransportExecuteInputSchema } from "@tools-contracts/transport-execute";
import { TRANSPORT_EXECUTE_TOOL_CONTRACT } from "@tools-contracts/transport-execute";

export const TRANSPORT_EXECUTE_TOOL = {
  ...TRANSPORT_EXECUTE_TOOL_CONTRACT,
  inputSchema: TransportExecuteInputSchema,
} as const;

