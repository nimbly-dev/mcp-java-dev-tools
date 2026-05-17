import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { RUN_SESSION_EXPORT_TOOL } from "@/tools/core/run_session_export/contract";
import { runSessionExportDomain } from "@/tools/core/run_session_export/domain";

export type RunSessionExportHandlerDeps = {
  workspaceRootAbs: string;
};

export function registerRunSessionExportTool(server: McpServer, deps: RunSessionExportHandlerDeps): void {
  server.registerTool(
    RUN_SESSION_EXPORT_TOOL.name,
    {
      description: RUN_SESSION_EXPORT_TOOL.description,
      inputSchema: RUN_SESSION_EXPORT_TOOL.inputSchema,
    },
    async ({
      sessionId,
      executionProfile,
      mode,
      includeResolvedSecrets,
      includeRuntimeStartup,
      includeHealthcheckGate,
    }) => {
      const request: {
        workspaceRootAbs: string;
        sessionId?: string;
        executionProfile?: string;
        mode: "ps1" | "sh" | "postman";
        includeResolvedSecrets?: boolean;
        includeRuntimeStartup?: boolean;
        includeHealthcheckGate?: boolean;
      } = {
        workspaceRootAbs: deps.workspaceRootAbs,
        mode,
      };
      if (typeof sessionId === "string" && sessionId.trim().length > 0) {
        request.sessionId = sessionId;
      }
      if (typeof executionProfile === "string" && executionProfile.trim().length > 0) {
        request.executionProfile = executionProfile;
      }
      if (typeof includeResolvedSecrets === "boolean") {
        request.includeResolvedSecrets = includeResolvedSecrets;
      }
      if (typeof includeRuntimeStartup === "boolean") {
        request.includeRuntimeStartup = includeRuntimeStartup;
      }
      if (typeof includeHealthcheckGate === "boolean") {
        request.includeHealthcheckGate = includeHealthcheckGate;
      }

      return await runSessionExportDomain(request);
    },
  );
}
