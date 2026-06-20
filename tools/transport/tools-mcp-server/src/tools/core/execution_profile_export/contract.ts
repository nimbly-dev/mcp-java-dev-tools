import { ExecutionProfileExportInputSchema } from "@/models/inputs";

export const EXECUTION_PROFILE_EXPORT_TOOL = {
  name: "execution_profile_export",
  description:
    "Export one persisted Execution Profile into deterministic replay artifacts. Supports regression step replay and performance workload replay with fail-closed mode routing.",
  inputSchema: ExecutionProfileExportInputSchema,
} as const;
