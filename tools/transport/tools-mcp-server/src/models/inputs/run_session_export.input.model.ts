import * as z from "zod/v4";

export const RunSessionExportInputSchema = {
  sessionId: z.string().optional().describe("Run-session identifier under .mcpjvm/<project>/exports/session-runs-exports/<session_id>."),
  executionProfile: z
    .string()
    .optional()
    .describe("Execution profile name used to resolve latest matching run session when sessionId is not provided."),
  mode: z.enum(["ps1", "sh", "postman"]).describe("Export output mode."),
  includeResolvedSecrets: z.boolean().optional().describe("Include resolved secret values in generated artifacts."),
  includeRuntimeStartup: z.boolean().optional().describe("Include runtime startup section in exported script."),
  includeHealthcheckGate: z.boolean().optional().describe("Include healthcheck gate section in exported script."),
} as const;
