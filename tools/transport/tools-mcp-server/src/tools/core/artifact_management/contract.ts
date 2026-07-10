import { ArtifactManagementRequestSchema } from "@/models/inputs/artifact_management";
import { ARTIFACT_MANAGEMENT_TOOL_CONTRACT } from "@tools-contracts/artifact-management";

export const ARTIFACT_MANAGEMENT_TOOL = {
  ...ARTIFACT_MANAGEMENT_TOOL_CONTRACT,
  inputSchema: ArtifactManagementRequestSchema,
} as const;
