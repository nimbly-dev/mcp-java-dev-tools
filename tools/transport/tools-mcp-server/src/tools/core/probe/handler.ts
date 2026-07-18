import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { ProbeRequestSchema } from "@tools-contracts/probe";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";
import {
  createProbeDomain,
  dispatchProbeAction,
  type ProbeActionRequest,
} from "@tools-feature-probe";
import { PROBE_TOOL } from "@/tools/core/probe/contract";
import type { ProbeRegistry } from "@tools-core/probe-registry";
import type { WorkspaceContext } from "@/config/workspace-context";

export type ProbeHandlerConfig = {
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeActuatePath: string;
  probeCapturePath: string;
  probeProfilerPath: string;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  getProbeRegistry?: () => ProbeRegistry | undefined;
  getWorkspaceRootAbs?: () => string | undefined;
  getWorkspaceContext?: () => WorkspaceContext;
};

export function registerProbeTools(server: McpServer, cfg: ProbeHandlerConfig): void {
  const domain = createProbeDomain(cfg);
  server.registerTool(
    PROBE_TOOL.name,
    {
      description: PROBE_TOOL.description,
      inputSchema: PROBE_TOOL.inputSchema,
    },
    async (rawInput) => {
      const workspaceRootAbs = cfg.getWorkspaceRootAbs?.();
      if (!workspaceRootAbs) {
        const reasonCode = cfg.getWorkspaceContext?.()?.reasonCode ?? "workspace_context_missing";
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
      if (!cfg.getProbeRegistry?.()) {
        const structuredContent = {
          resultType: "report",
          status: "probe_config_missing",
          reasonCode: "probe_config_missing",
          reason: "The workspace has no canonical .mcpjvm/probe-config.json.",
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }
      const parsed = ProbeRequestSchema.safeParse(rawInput);
      if (!parsed.success) {
        const issues = parsed.error.issues.map((issue) => ({
          path: issue.path.join("."),
          code: issue.code,
          message: issue.message,
        }));
        const structuredContent = {
          resultType: "report",
          status: MCP_REQUEST_REASON_CODES.probeInvalid,
          reasonCode: MCP_REQUEST_REASON_CODES.probeInvalid,
          nextActionCode: MCP_REQUEST_REASON_CODES.probeInvalid,
          reason: "probe input schema validation failed",
          reasonMeta: { failedStep: "input_validation", issues },
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }

      return await dispatchProbeAction(domain, parsed.data as ProbeActionRequest);
    },
  );
}
