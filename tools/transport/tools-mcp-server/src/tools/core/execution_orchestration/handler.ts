import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ExecutionOrchestrationRequestSchema } from "@/models/inputs/execution_orchestration";

import { EXECUTION_ORCHESTRATION_TOOL } from "@/tools/core/execution_orchestration/contract";
import { executionOrchestrationDomain } from "@/tools/core/execution_orchestration/domain";

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
          status: "execution_request_invalid",
          reasonCode: "execution_request_invalid",
          nextActionCode: "execution_request_invalid",
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
        payload: parsed.data.input,
      });
    },
  );
}
