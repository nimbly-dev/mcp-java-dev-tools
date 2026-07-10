import * as z from "zod/v4";

export const ExecutionProfileExportInputSchema = {
  projectName: z
    .string()
    .optional()
    .describe("Optional .mcpjvm project folder name for deterministic project selection in multi-project workspaces."),
  exportId: z.string().optional().describe("Optional export identifier/label. When omitted, the tool derives one from profile selection."),
  executionProfile: z
    .string()
    .optional()
    .describe("Execution profile name used to resolve export source from current project plan state."),
  planName: z
    .string()
    .optional()
    .describe("Plan name used to resolve the containing execution profile from current project plan state."),
  when: z.string().optional().describe("Optional date/time hint used only for deterministic export labeling (ISO-8601 recommended)."),
  mode: z.enum(["ps1", "sh", "postman"]).optional().describe("Export output mode. Performance profiles support ps1 and sh only."),
  type: z
    .enum(["ps1", "sh", "postman"])
    .optional()
    .describe("Alias for mode. When both mode and type are supplied, values must match."),
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
