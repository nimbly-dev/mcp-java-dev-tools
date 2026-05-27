import * as z from "zod/v4";

export const ExecutionProfileExportInputSchema = {
  exportId: z.string().optional().describe("Optional export identifier/label. When omitted, the tool derives one from profile selection."),
  executionProfile: z
    .string()
    .optional()
    .describe("Execution profile name used to resolve export source from current project plan state."),
  planName: z.string().optional().describe("Regression plan name used to resolve containing execution profile from current project plan state."),
  when: z.string().optional().describe("Optional date/time hint used only for deterministic export labeling (ISO-8601 recommended)."),
  mode: z.enum(["ps1", "sh", "postman"]).describe("Export output mode."),
  includeResolvedSecrets: z.boolean().optional().describe("Include resolved secret values in generated artifacts."),
  includeRuntimeStartup: z.boolean().optional().describe("Include runtime startup section in exported script."),
  includeHealthcheckGate: z.boolean().optional().describe("Include healthcheck gate section in exported script."),
  contextBindings: z
    .record(z.string(), z.string())
    .optional()
    .describe("Optional prerequisite-to-env-key binding map for export-time variable resolution (for example auth.bearer -> AUTH_BEARER_TOKEN)."),
  contextValues: z
    .record(z.string(), z.string())
    .optional()
    .describe("Optional direct prerequisite/context values for this export invocation (run-scoped, non-env)."),
} as const;
