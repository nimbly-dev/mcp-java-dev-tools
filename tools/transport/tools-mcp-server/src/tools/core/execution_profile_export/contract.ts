import { ExecutionProfileExportInputSchema } from "@/models/inputs";

export const EXECUTION_PROFILE_EXPORT_TOOL = {
  name: "execution_profile_export",
  description:
    "Export one persisted Execution Profile into deterministic replay artifacts. Supports mode routing with fail-closed behavior for unsupported modes.",
  inputSchema: ExecutionProfileExportInputSchema,
} as const;
