import { ArtifactManagementRequestSchema } from "@/models/inputs/artifact_management";

export const ARTIFACT_MANAGEMENT_TOOL = {
  name: "artifact_management",
  description:
    "Unified .mcpjvm artifact lifecycle MCP Tool with strict per-artifactType action allowlists and deterministic fail-closed outputs.",
  inputSchema: ArtifactManagementRequestSchema,
} as const;
