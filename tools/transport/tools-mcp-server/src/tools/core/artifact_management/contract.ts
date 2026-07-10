import { ArtifactManagementRequestSchema } from "@tools-contracts/artifact-management";
import { ARTIFACT_MANAGEMENT_TOOL_CONTRACT } from "@tools-contracts/artifact-management";

export const ARTIFACT_MANAGEMENT_TOOL = {
  ...ARTIFACT_MANAGEMENT_TOOL_CONTRACT,
  inputSchema: ArtifactManagementRequestSchema,
} as const;
