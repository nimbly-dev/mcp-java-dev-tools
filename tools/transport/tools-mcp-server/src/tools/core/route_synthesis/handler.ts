import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import {
  RouteSynthesisActionSchema,
  type RouteSynthesisAction,
} from "@tools-contracts/route-synthesis";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";
import type { RouteSynthesisHandlerDeps } from "@tools-feature-route-synthesis";
import { ROUTE_SYNTHESIS_TOOL } from "@/tools/core/route_synthesis/contract";
import { dispatchRouteSynthesisAction } from "@tools-feature-route-synthesis";
import type { WorkspaceContext } from "@/config/workspace-context";

type RouteSynthesisTransportDeps = RouteSynthesisHandlerDeps & {
  getWorkspaceContext?: () => WorkspaceContext;
};

function toInvalidRequestResponse(message: string) {
  const structuredContent = {
    resultType: "report",
    status: "blocked_invalid",
    reasonCode: MCP_REQUEST_REASON_CODES.routeSynthesisInvalid,
    nextActionCode: "fix_route_synthesis_request",
    failedStep: "input_validation",
    reason: message,
    nextAction: "Provide a valid route_synthesis action with typed input and rerun the MCP Tool.",
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export async function runRouteSynthesis(
  input: Record<string, unknown>,
  deps: RouteSynthesisTransportDeps,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const request = input as { action?: unknown; input?: unknown };
  const parsedAction = RouteSynthesisActionSchema.safeParse(request.action);
  if (!parsedAction.success) {
    return toInvalidRequestResponse(parsedAction.error.message);
  }
  if (!request.input || typeof request.input !== "object" || Array.isArray(request.input)) {
    return toInvalidRequestResponse("route_synthesis input must be a JSON object.");
  }
  if (!deps.config.workspaceRootAbs) {
    const reasonCode = deps.getWorkspaceContext?.()?.reasonCode ?? "workspace_context_missing";
    const reason =
      reasonCode === "workspace_context_ambiguous"
        ? "Multiple MCP Roots contain canonical workspace state; select one workspace root explicitly."
        : "No MCP workspace root is bound to this session.";
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              resultType: "report",
              status: reasonCode,
              reasonCode,
              nextActionCode: "bind_workspace_root",
              failedStep: "workspace_resolution",
              reason,
            },
            null,
            2,
          ),
        },
      ],
      structuredContent: {
        resultType: "report",
        status: reasonCode,
        reasonCode,
        nextActionCode: "bind_workspace_root",
        failedStep: "workspace_resolution",
        reason,
      },
    };
  }
  const workspaceRootAbs = deps.config.workspaceRootAbs;

  const action: RouteSynthesisAction = parsedAction.data;
  return await dispatchRouteSynthesisAction({
    action,
    input: request.input as Record<string, unknown>,
    deps: { ...deps, config: { ...deps.config, workspaceRootAbs } },
  });
}

export function registerRouteSynthesisTool(
  server: McpServer,
  deps: RouteSynthesisTransportDeps,
): void {
  server.registerTool(
    ROUTE_SYNTHESIS_TOOL.name,
    {
      description: ROUTE_SYNTHESIS_TOOL.description,
      inputSchema: ROUTE_SYNTHESIS_TOOL.inputSchema,
    },
    async (input) => await runRouteSynthesis(input as Record<string, unknown>, deps),
  );
}
