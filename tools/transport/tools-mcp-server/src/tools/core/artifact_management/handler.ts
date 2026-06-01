import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ArtifactManagementRequestSchema } from "@/models/inputs/artifact_management";
import { ARTIFACT_MANAGEMENT_TOOL } from "@/tools/core/artifact_management/contract";
import { artifactManagementDomain } from "@/tools/core/artifact_management/domain";
import { buildFailClosedArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";

export type ArtifactManagementHandlerDeps = {
  workspaceRootAbs: string;
};

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function normalizeInputAliases(raw: unknown): unknown {
  const envelope = asRecord(raw);
  if (!envelope) return raw;
  const input = asRecord(envelope.input);
  if (!input) return raw;

  const normalizedInput: Record<string, unknown> = { ...input };
  if (typeof normalizedInput.projectName !== "string" && typeof input.project_name === "string") {
    normalizedInput.projectName = input.project_name;
  }
  if (typeof normalizedInput.executionProfile !== "string" && typeof input.execution_profile === "string") {
    normalizedInput.executionProfile = input.execution_profile;
  }
  if (typeof normalizedInput.planName !== "string" && typeof input.plan_name === "string") {
    normalizedInput.planName = input.plan_name;
  }
  if (typeof normalizedInput.runId !== "string" && typeof input.run_id === "string") {
    normalizedInput.runId = input.run_id;
  }

  return {
    ...envelope,
    input: normalizedInput,
  };
}

export function registerArtifactManagementTool(server: McpServer, deps: ArtifactManagementHandlerDeps): void {
  server.registerTool(
    ARTIFACT_MANAGEMENT_TOOL.name,
    {
      description: ARTIFACT_MANAGEMENT_TOOL.description,
      inputSchema: ARTIFACT_MANAGEMENT_TOOL.inputSchema,
    },
    async (input) => {
      const parsed = ArtifactManagementRequestSchema.safeParse(normalizeInputAliases(input));
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
