import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { TRANSPORT_EXECUTE_TOOL } from "@/tools/core/transport_execute/contract";
import { dispatchTransportExecutionAction } from "@tools-feature-transport-execution";

export type TransportExecuteHandlerDeps = {
  allowNonWrappedExecutable: () => boolean;
};

export function registerTransportExecuteTool(server: McpServer, deps: TransportExecuteHandlerDeps): void {
  server.registerTool(
    TRANSPORT_EXECUTE_TOOL.name,
    {
      description: TRANSPORT_EXECUTE_TOOL.description,
      inputSchema: TRANSPORT_EXECUTE_TOOL.inputSchema,
    },
    async ({ protocol, request, options }) => {
      return await dispatchTransportExecutionAction({
        protocol,
        request,
        wrappedOnly: options?.wrappedOnly !== false,
        allowNonWrappedExecutable: deps.allowNonWrappedExecutable(),
      });
    },
  );
}

