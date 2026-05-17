#!/usr/bin/env node

import * as fs from "node:fs";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { loadConfigFromEnvAndArgs } from "@/config/server-config";
import { CONFIG_DEFAULTS } from "@/config/defaults";
import { loadProbeRegistry } from "@/config/probe-registry";
import { registerProjectContextValidateTool } from "@/tools/core/project_context_validate/handler";
import { registerProbeCheckTool } from "@/tools/core/probe_check/handler";
import { registerTargetInferTool } from "@/tools/core/target_infer/handler";
import { registerRecipeCreateTool } from "@/tools/core/recipe_generate/handler";
import { registerProbeTools } from "@/tools/core/probe/handler";
import { registerTransportExecuteTool } from "@/tools/core/transport_execute/handler";
import {
  registerProbeRegistryTools,
  type ProbeRegistrySummary,
} from "@/tools/core/probe_registry/handler";
import { registerRunSessionExportTool } from "@/tools/core/run_session_export/handler";

async function main() {
  const cfg = loadConfigFromEnvAndArgs(process.argv);
  const probeStatusPath = cfg.probeStatusPath;
  const probeResetPath = cfg.probeResetPath;
  const probeActuatePath = CONFIG_DEFAULTS.PROBE_ACTUATE_PATH;
  const probeCapturePath = cfg.probeCapturePath;

  let activeRegistry = cfg.probeRegistry;
  let registryWatch: fs.FSWatcher | undefined;
  let registryReloadTimer: NodeJS.Timeout | undefined;
  let lastRegistryContent: string | undefined;
  let lastReloadAt: string | undefined;
  let lastReloadStatus: "ok" | "error" | undefined;
  let lastReloadError: string | undefined;

  const reloadRegistryInternal = (source: "manual" | "watch"): ProbeRegistrySummary | undefined => {
    if (!activeRegistry) return undefined;
    try {
      const raw = fs.readFileSync(activeRegistry.configFileAbs, "utf8");
      if (source === "watch" && typeof lastRegistryContent === "string" && raw === lastRegistryContent) {
        return toRegistrySummary();
      }
      const nextRegistry = loadProbeRegistry({
        filePath: activeRegistry.configFileAbs,
        workspaceRootAbs: cfg.workspaceRootAbs,
      });
      activeRegistry = nextRegistry;
      lastRegistryContent = raw;
      lastReloadAt = new Date().toISOString();
      lastReloadStatus = "ok";
      lastReloadError = undefined;
      if (source === "watch") {
        console.error(
          `probe registry auto-reloaded: profile=${activeRegistry.activeProfile} file=${activeRegistry.configFileAbs}`,
        );
      }
      return toRegistrySummary();
    } catch (err) {
      lastReloadAt = new Date().toISOString();
      lastReloadStatus = "error";
      lastReloadError = err instanceof Error ? err.message : String(err);
      console.error(`probe registry reload failed (${source}): ${lastReloadError}`);
      return toRegistrySummary();
    }
  };

  const currentBaseUrl = () => {
    if (!activeRegistry) return cfg.probeBaseUrl;
    const probe = activeRegistry.probesById.get(activeRegistry.defaultProbeId);
    return probe?.baseUrl ?? cfg.probeBaseUrl;
  };
  const toRegistrySummary = (): ProbeRegistrySummary | undefined => {
    if (!activeRegistry) return undefined;
    return {
      configFileAbs: activeRegistry.configFileAbs,
      activeProfile: activeRegistry.activeProfile,
      profileSource: activeRegistry.profileSource,
      defaultProbeId: activeRegistry.defaultProbeId,
      probeCount: activeRegistry.probesById.size,
      allowNonWrappedExecutable: activeRegistry.allowNonWrappedExecutable,
      ...(lastReloadAt ? { lastReloadAt } : {}),
      ...(lastReloadStatus ? { lastReloadStatus } : {}),
      ...(lastReloadError ? { lastReloadError } : {}),
      probes: Array.from(activeRegistry.probesById.values()).map((probe) => ({
        id: probe.id,
        baseUrl: probe.baseUrl,
        ...(probe.description ? { description: probe.description } : {}),
        include: probe.include,
        exclude: probe.exclude,
        ...(probe.runtime ? { runtime: probe.runtime } : {}),
      })),
    };
  };
  const reloadRegistry = (): ProbeRegistrySummary | undefined => {
    return reloadRegistryInternal("manual");
  };

  const setupRegistryWatcher = () => {
    if (!activeRegistry) return;
    try {
      const cfgPath = activeRegistry.configFileAbs;
      lastRegistryContent = fs.readFileSync(cfgPath, "utf8");
      registryWatch = fs.watch(cfgPath, () => {
        if (registryReloadTimer) clearTimeout(registryReloadTimer);
        registryReloadTimer = setTimeout(() => {
          reloadRegistryInternal("watch");
        }, 350);
      });
      console.error(`probe registry watch enabled: ${cfgPath}`);
    } catch (err) {
      console.error(
        `probe registry watch disabled: ${err instanceof Error ? err.message : String(err)}`,
      );
    }
  };

  const server = new McpServer({
    name: "mcp-java-dev-tools",
    version: "0.1.4",
  });

  server.registerResource(
    "status",
    "mcp-java-dev-tools://status",
    { mimeType: "application/json", description: "Server status and defaults" },
    async () => {
      const payload = {
        ok: true,
        name: "mcp-java-dev-tools",
        version: "0.1.4",
        workspaceRoot: cfg.workspaceRootAbs,
        workspaceRootSource: cfg.workspaceRootSource,
        probe: {
          baseUrl: currentBaseUrl(),
          statusPath: probeStatusPath,
          resetPath: probeResetPath,
          actuatePath: probeActuatePath,
          capturePath: probeCapturePath,
          waitMaxRetriesDefault: cfg.probeWaitMaxRetries,
          waitUnreachableRetryEnabled: cfg.probeWaitUnreachableRetryEnabled,
          waitUnreachableMaxRetries: cfg.probeWaitUnreachableMaxRetries,
          ...(activeRegistry
            ? {
                activeProfile: activeRegistry.activeProfile,
                profileSource: activeRegistry.profileSource,
                defaultProbeId: activeRegistry.defaultProbeId,
                registryProbeCount: activeRegistry.probesById.size,
                allowNonWrappedExecutable: activeRegistry.allowNonWrappedExecutable,
              }
            : {}),
        },
        recipe: {
          hasCustomTemplate: false,
        },
        auth: {
          credentialDiscovery: "disabled",
        },
        time: new Date().toISOString(),
      };
      return {
        contents: [
          {
            uri: "mcp-java-dev-tools://status",
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    },
  );

  server.registerTool(
    "debug_check",
    {
      description: "Sanity check: confirms the MCP server is reachable.",
      inputSchema: {},
    },
    async () => {
      const structuredContent = {
        ok: true,
        serverTime: new Date().toISOString(),
        version: "0.1.4",
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    },
  );

  registerProjectContextValidateTool(server);
  registerProbeCheckTool(server, {
    probeBaseUrl: currentBaseUrl(),
    probeStatusPath,
    probeResetPath,
    getProbeRegistry: () => activeRegistry,
  });
  registerTargetInferTool(server, {
    config: cfg,
  });
  registerRecipeCreateTool(server, {
    probeBaseUrl: currentBaseUrl(),
    probeStatusPath,
    workspaceRootAbs: cfg.workspaceRootAbs,
    getProbeRegistry: () => activeRegistry,
  });
  registerProbeTools(server, {
    probeBaseUrl: currentBaseUrl(),
    probeStatusPath,
    probeResetPath,
    probeActuatePath,
    probeCapturePath,
    probeWaitMaxRetries: cfg.probeWaitMaxRetries,
    probeWaitUnreachableRetryEnabled: cfg.probeWaitUnreachableRetryEnabled,
    probeWaitUnreachableMaxRetries: cfg.probeWaitUnreachableMaxRetries,
    getProbeRegistry: () => activeRegistry,
  });
  registerProbeRegistryTools(server, {
    getRegistrySummary: () => toRegistrySummary(),
    reloadRegistry: () => reloadRegistry(),
  });
  registerTransportExecuteTool(server, {
    allowNonWrappedExecutable: () => activeRegistry?.allowNonWrappedExecutable ?? false,
  });
  registerRunSessionExportTool(server, {
    workspaceRootAbs: cfg.workspaceRootAbs,
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
  setupRegistryWatcher();
  console.error(
    `mcp-java-dev-tools 0.1.4 running (stdio). workspaceRoot=${cfg.workspaceRootAbs} probeBaseUrl=${currentBaseUrl()}`,
  );
}

main().catch((err) => {
  console.error("Server error:", err);
  process.exit(1);
});
