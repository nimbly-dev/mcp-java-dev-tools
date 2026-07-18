import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { EXECUTION_PROFILE_EXPORT_TOOL } from "@/tools/core/execution_profile_export/contract";
import { dispatchExecutionProfileExportAction } from "@tools-export-execution-profile";
import type { WorkspaceContext } from "@/config/workspace-context";

export type ExecutionProfileExportHandlerDeps = {
  workspaceRootAbs: string | undefined;
  getWorkspaceRootAbs?: () => string | undefined;
  getWorkspaceContext?: () => WorkspaceContext;
};

export function registerExecutionProfileExportTool(
  server: McpServer,
  deps: ExecutionProfileExportHandlerDeps,
): void {
  server.registerTool(
    EXECUTION_PROFILE_EXPORT_TOOL.name,
    {
      description: EXECUTION_PROFILE_EXPORT_TOOL.description,
      inputSchema: EXECUTION_PROFILE_EXPORT_TOOL.inputSchema,
    },
    async ({
      projectName,
      exportId,
      executionProfile,
      planName,
      when,
      mode,
      type,
      includeResolvedSecrets,
      includeRuntimeStartup,
      includeHealthcheckGate,
      contextBindings,
      contextValues,
    }) => {
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
      const request: {
        workspaceRootAbs: string;
        projectName?: string;
        exportId?: string;
        executionProfile?: string;
        planName?: string;
        when?: string;
        mode?: "ps1" | "sh" | "postman";
        type?: "ps1" | "sh" | "postman";
        includeResolvedSecrets?: boolean;
        includeRuntimeStartup?: boolean;
        includeHealthcheckGate?: boolean;
        contextBindings?: Record<string, string>;
        contextValues?: Record<string, string>;
      } = {
        workspaceRootAbs,
      };
      if (typeof mode === "string") {
        request.mode = mode;
      }
      if (typeof type === "string") {
        request.type = type;
      }
      if (typeof exportId === "string" && exportId.trim().length > 0) {
        request.exportId = exportId;
      }
      if (typeof projectName === "string" && projectName.trim().length > 0) {
        request.projectName = projectName.trim();
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
      if (
        contextBindings &&
        typeof contextBindings === "object" &&
        !Array.isArray(contextBindings)
      ) {
        request.contextBindings = Object.fromEntries(
          Object.entries(contextBindings)
            .filter(
              ([k, v]) =>
                typeof k === "string" &&
                k.trim().length > 0 &&
                typeof v === "string" &&
                v.trim().length > 0,
            )
            .map(([k, v]) => [k.trim(), v.trim()]),
        );
      }
      if (contextValues && typeof contextValues === "object" && !Array.isArray(contextValues)) {
        request.contextValues = Object.fromEntries(
          Object.entries(contextValues)
            .filter(
              ([k, v]) => typeof k === "string" && k.trim().length > 0 && typeof v === "string",
            )
            .map(([k, v]) => [k.trim(), v]),
        );
      }

      return await dispatchExecutionProfileExportAction(request);
    },
  );
}
