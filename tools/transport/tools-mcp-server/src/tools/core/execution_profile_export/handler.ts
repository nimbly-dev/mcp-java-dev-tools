import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { EXECUTION_PROFILE_EXPORT_TOOL } from "@/tools/core/execution_profile_export/contract";
import { executionProfileExportDomain } from "@/tools/core/execution_profile_export/domain";

export type ExecutionProfileExportHandlerDeps = {
  workspaceRootAbs: string;
};

export function registerExecutionProfileExportTool(server: McpServer, deps: ExecutionProfileExportHandlerDeps): void {
  server.registerTool(
    EXECUTION_PROFILE_EXPORT_TOOL.name,
    {
      description: EXECUTION_PROFILE_EXPORT_TOOL.description,
      inputSchema: EXECUTION_PROFILE_EXPORT_TOOL.inputSchema,
    },
    async ({
      exportId,
      executionProfile,
      planName,
      when,
      mode,
      includeResolvedSecrets,
      includeRuntimeStartup,
      includeHealthcheckGate,
    }) => {
      const request: {
        workspaceRootAbs: string;
        exportId?: string;
        executionProfile?: string;
        planName?: string;
        when?: string;
        mode: "ps1" | "sh" | "postman";
        includeResolvedSecrets?: boolean;
        includeRuntimeStartup?: boolean;
        includeHealthcheckGate?: boolean;
      } = {
        workspaceRootAbs: deps.workspaceRootAbs,
        mode,
      };
      if (typeof exportId === "string" && exportId.trim().length > 0) {
        request.exportId = exportId;
      }
      if (typeof executionProfile === "string" && executionProfile.trim().length > 0) {
        request.executionProfile = executionProfile;
      }
      if (typeof planName === "string" && planName.trim().length > 0) {
        request.planName = planName;
      }
      if (typeof when === "string" && when.trim().length > 0) {
        request.when = when;
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

      return await executionProfileExportDomain(request);
    },
  );
}
