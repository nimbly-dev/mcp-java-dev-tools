import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ArtifactManagementRequestSchema } from "@/models/inputs/artifact_management";
import { ARTIFACT_MANAGEMENT_TOOL } from "@/tools/core/artifact_management/contract";
import { artifactManagementDomain } from "@/tools/core/artifact_management/domain";
import { buildFailClosedArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";

export type ArtifactManagementHandlerDeps = {
  workspaceRootAbs: string;
};

export function registerArtifactManagementTool(server: McpServer, deps: ArtifactManagementHandlerDeps): void {
  server.registerTool(
    ARTIFACT_MANAGEMENT_TOOL.name,
    {
      description: ARTIFACT_MANAGEMENT_TOOL.description,
      inputSchema: ARTIFACT_MANAGEMENT_TOOL.inputSchema,
    },
    async (input) => {
      const parsed = ArtifactManagementRequestSchema.safeParse(input);
      if (!parsed.success) {
        return buildFailClosedArtifactResponse({
          reasonCode: "artifact_request_invalid",
          reason: "artifact_management input schema validation failed",
          reasonMeta: {
            failedStep: "input_validation",
            issues: parsed.error.issues.map((issue) => ({
              path: issue.path.join("."),
              code: issue.code,
              message: issue.message,
            })),
          },
        });
      }
      return await artifactManagementDomain({
        workspaceRootAbs: deps.workspaceRootAbs,
        request: parsed.data,
      });
    },
  );
}
