import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { PROBE_REGISTRY_TOOLS } from "@/tools/core/probe_registry/contract";

export type ProbeRegistrySummary = {
  configFileAbs: string;
  activeProfile: string;
  profileSource: "env" | "workspace" | "default";
  defaultProbeId: string;
  probeCount: number;
  allowNonWrappedExecutable: boolean;
  lastReloadAt?: string;
  lastReloadStatus?: "ok" | "error";
  lastReloadError?: string;
  probes: Array<{
    id: string;
    baseUrl: string;
    description?: string;
    include: string[];
    exclude: string[];
    runtime?: Record<string, unknown>;
  }>;
};

export type ProbeRegistryToolDeps = {
  getRegistrySummary: () => ProbeRegistrySummary | undefined;
  reloadRegistry: () => ProbeRegistrySummary | undefined;
};

export function registerProbeRegistryTools(server: McpServer, deps: ProbeRegistryToolDeps): void {
  server.registerTool(
    PROBE_REGISTRY_TOOLS.list.name,
    {
      description: PROBE_REGISTRY_TOOLS.list.description,
      inputSchema: PROBE_REGISTRY_TOOLS.list.inputSchema,
    },
    async () => {
      const summary = deps.getRegistrySummary();
      const structuredContent = summary
        ? {
            resultType: "probe_registry",
            status: "ok",
            ...summary,
          }
        : {
            resultType: "probe_registry",
            status: "not_configured",
            reasonCode: "probe_registry_not_configured",
            nextActionCode: "set_probe_registry_config",
            nextAction:
              "Place .mcpjvm/probe-config.json under the workspace (or a parent directory), then restart MCP server.",
          };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    },
  );

  server.registerTool(
    PROBE_REGISTRY_TOOLS.reload.name,
    {
      description: PROBE_REGISTRY_TOOLS.reload.description,
      inputSchema: PROBE_REGISTRY_TOOLS.reload.inputSchema,
    },
    async () => {
      const summary = deps.reloadRegistry();
      const structuredContent = summary
        ? {
            resultType: "probe_registry",
            status: "reloaded",
            ...summary,
          }
        : {
            resultType: "probe_registry",
            status: "not_configured",
            reasonCode: "probe_registry_not_configured",
            nextActionCode: "set_probe_registry_config",
            nextAction:
              "Place .mcpjvm/probe-config.json under the workspace (or a parent directory), then restart MCP server.",
          };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    },
  );
}
