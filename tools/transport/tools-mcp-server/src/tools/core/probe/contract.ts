import { ProbeInputSchema } from "@tools-contracts/probe";
import { PROBE_TOOL_CONTRACT } from "@tools-contracts/probe";

export const PROBE_TOOL = {
  ...PROBE_TOOL_CONTRACT,
  inputSchema: ProbeInputSchema,
} as const;
