import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ExecutionOrchestrationRequestSchema } from "@tools-contracts/execution-orchestration";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";

import { EXECUTION_ORCHESTRATION_TOOL } from "@/tools/core/execution_orchestration/contract";
import { executionOrchestrationDomain } from "@tools-feature-execution-orchestration";

export type ExecutionOrchestrationHandlerDeps = {
  workspaceRootAbs: string;
};

export function registerExecutionOrchestrationTool(
  server: McpServer,
  deps: ExecutionOrchestrationHandlerDeps,
): void {
  server.registerTool(
    EXECUTION_ORCHESTRATION_TOOL.name,
    {
      description: EXECUTION_ORCHESTRATION_TOOL.description,
      inputSchema: EXECUTION_ORCHESTRATION_TOOL.inputSchema,
    },
    async (rawInput) => {
      const parsed = ExecutionOrchestrationRequestSchema.safeParse(rawInput);
      if (!parsed.success) {
        const issues = parsed.error.issues.map((issue) => ({
          path: issue.path.join("."),
          code: issue.code,
          message: issue.message,
        }));
        const structuredContent = {
          resultType: "report",
          status: MCP_REQUEST_REASON_CODES.executionInvalid,
          reasonCode: MCP_REQUEST_REASON_CODES.executionInvalid,
          nextActionCode: MCP_REQUEST_REASON_CODES.executionInvalid,
          reason: "execution_orchestration input schema validation failed",
          reasonMeta: { failedStep: "input_validation", issues },
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }

      return await executionOrchestrationDomain({
        workspaceRootAbs: deps.workspaceRootAbs,
        action: parsed.data.action,
        payload: {
          projectName: parsed.data.input.projectName,
          executionProfile: parsed.data.input.executionProfile,
          ...(typeof parsed.data.input.suiteRunId === "string" ? { suiteRunId: parsed.data.input.suiteRunId } : {}),
          ...(typeof parsed.data.input.maxPlansPerCall === "number"
            ? { maxPlansPerCall: parsed.data.input.maxPlansPerCall }
            : {}),
        },
      });
    },
  );
}
