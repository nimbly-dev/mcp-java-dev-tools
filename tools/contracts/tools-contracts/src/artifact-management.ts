export const ARTIFACT_MANAGEMENT_TOOL_CONTRACT = {
  name: "artifact_management",
  description:
    "Unified .mcpjvm Artifact lifecycle MCP Tool with strict per-artifactType action allowlists and deterministic fail-closed outputs.",
} as const;

export * from "./inputs/artifact_management";
export {
  ARTIFACT_ACTION_ALLOWLIST,
  ArtifactActionSchema,
  ArtifactTypeSchema,
  type ArtifactAction,
  type ArtifactType,
} from "./inputs/artifact_management/shared/actions.model";
