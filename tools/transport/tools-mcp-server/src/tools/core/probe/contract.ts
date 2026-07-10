import { ProbeInputSchema } from "@/models/inputs";
import { PROBE_TOOL_CONTRACT } from "@tools-contracts/probe";

export const PROBE_TOOL = {
  ...PROBE_TOOL_CONTRACT,
  inputSchema: ProbeInputSchema,
} as const;
