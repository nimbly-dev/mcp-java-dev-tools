import { ProbeInputSchema } from "@/models/inputs";

export const PROBE_TOOL = {
  name: "probe",
  description:
    "Canonical live Probe MCP Tool. Use action=check|status|reset|wait_for_hit|capture|actuate for runtime diagnostics, strict line verification, capture retrieval, and session actuation.",
  inputSchema: ProbeInputSchema,
} as const;
