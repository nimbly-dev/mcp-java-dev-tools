import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import {
  RouteSynthesisActionSchema,
  type RouteSynthesisAction,
} from "@tools-contracts/route-synthesis";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";
import type { RouteSynthesisHandlerDeps } from "@/models/route_synthesis.model";
import { ROUTE_SYNTHESIS_TOOL } from "@/tools/core/route_synthesis/contract";
import { routeSynthesisDomain } from "@tools-feature-route-synthesis";

function toInvalidRequestResponse(message: string) {
  const structuredContent = {
    resultType: "report",
    status: "blocked_invalid",
    reasonCode: MCP_REQUEST_REASON_CODES.routeSynthesisInvalid,
    nextActionCode: "fix_route_synthesis_request",
    failedStep: "input_validation",
    reason: message,
    nextAction:
      "Provide a valid route_synthesis action with typed input and rerun the MCP Tool.",
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export async function runRouteSynthesis(
  input: Record<string, unknown>,
  deps: RouteSynthesisHandlerDeps,
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

  const action: RouteSynthesisAction = parsedAction.data;
  return await routeSynthesisDomain({
    action,
    input: request.input as Record<string, unknown>,
    deps,
  });
}

export function registerRouteSynthesisTool(
  server: McpServer,
  deps: RouteSynthesisHandlerDeps,
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
