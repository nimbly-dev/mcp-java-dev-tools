import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ExecutionOrchestrationRequestSchema } from "@tools-contracts/execution-orchestration";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";
import type { ProbeDomainConfig } from "@tools-feature-probe";

import { EXECUTION_ORCHESTRATION_TOOL } from "@/tools/core/execution_orchestration/contract";
import { dispatchExecutionOrchestrationAction } from "@tools-feature-execution-orchestration";
import type { WorkspaceContext } from "@/config/workspace-context";

export type ExecutionOrchestrationHandlerDeps = {
  workspaceRootAbs: string | undefined;
  getWorkspaceRootAbs?: () => string | undefined;
  getWorkspaceContext?: () => WorkspaceContext;
  probeConfig: ProbeDomainConfig;
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

      const workspaceRootAbs = deps.getWorkspaceRootAbs?.() ?? deps.workspaceRootAbs;
      if (!workspaceRootAbs) {
        const reasonCode = deps.getWorkspaceContext?.()?.reasonCode ?? "workspace_context_missing";
        const structuredContent = {
          resultType: "report",
          status: reasonCode,
          reasonCode,
          reason:
            reasonCode === "workspace_context_ambiguous"
              ? "Multiple MCP Roots contain canonical workspace state; select one workspace root explicitly."
              : "No MCP workspace root is bound to this session.",
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }
      return await dispatchExecutionOrchestrationAction({
        workspaceRootAbs,
        probeConfig: deps.probeConfig,
        request: parsed.data,
      });
    },
  );
}
