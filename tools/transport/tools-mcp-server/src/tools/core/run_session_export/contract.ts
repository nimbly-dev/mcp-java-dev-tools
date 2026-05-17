import { RunSessionExportInputSchema } from "@/models/inputs";

export const RUN_SESSION_EXPORT_TOOL = {
  name: "run_session_export",
  description:
    "Export one persisted run session into deterministic replay artifacts. Supports mode routing with fail-closed behavior for unsupported modes.",
  inputSchema: RunSessionExportInputSchema,
} as const;
