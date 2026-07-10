#!/usr/bin/env node

import * as fs from "node:fs";
import path from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { loadConfigFromEnvAndArgs } from "@/config/server-config";
import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";
import { loadProbeRegistry, summarizeProbeRegistry, type ProbeRegistrySummary } from "@tools-core/probe-registry";
import { registerRouteSynthesisTool } from "@/tools/core/route_synthesis/handler";
import { registerProbeTools } from "@/tools/core/probe/handler";
import { registerTransportExecuteTool } from "@/tools/core/transport_execute/handler";
import { registerExecutionProfileExportTool } from "@/tools/core/execution_profile_export/handler";
import { registerArtifactManagementTool } from "@/tools/core/artifact_management/handler";
import { registerExecutionOrchestrationTool } from "@/tools/core/execution_orchestration/handler";

function resolveBuildFingerprint(): string {
  const distServerAbs = path.resolve(__dirname, "../../../../server.js");
  try {
    const stat = fs.statSync(distServerAbs);
    return `dist-server-mtime:${stat.mtime.toISOString()}`;
  } catch {
    return "dist-server-mtime:unknown";
  }
}

function resolveServerVersion(): string {
  const fromEnv = process.env.MCP_JAVA_DEV_TOOLS_VERSION;
  if (typeof fromEnv === "string" && fromEnv.trim().length > 0) {
    return fromEnv.trim();
  }

  const candidates = [
    path.resolve(__dirname, "../../../../package.json"),
    path.resolve(__dirname, "../../../../../package.json"),
    path.resolve(process.cwd(), "package.json"),
  ];
  for (const fileAbs of candidates) {
    try {
      const raw = fs.readFileSync(fileAbs, "utf8");
      const parsed = JSON.parse(raw) as { version?: unknown };
      if (typeof parsed.version === "string" && parsed.version.trim().length > 0) {
        return parsed.version.trim();
      }
    } catch {
      // Try next candidate.
    }
  }
  throw new Error(
    "server_version_unresolved: unable to resolve MCP server version from MCP_JAVA_DEV_TOOLS_VERSION or package.json",
  );
}

async function main() {
  const serverVersion = resolveServerVersion();
  const buildFingerprint = resolveBuildFingerprint();
  const cfg = loadConfigFromEnvAndArgs(process.argv);
  const probeStatusPath = cfg.probeStatusPath;
  const probeResetPath = cfg.probeResetPath;
  const probeActuatePath = CONFIG_DEFAULTS.PROBE_ACTUATE_PATH;
  const probeCapturePath = cfg.probeCapturePath;
  const probeProfilerPath = CONFIG_DEFAULTS.PROBE_PROFILER_PATH;

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
    const probe =
      typeof activeRegistry.implicitProbeId === "string"
        ? activeRegistry.probesById.get(activeRegistry.implicitProbeId)
        : undefined;
    return probe?.baseUrl ?? cfg.probeBaseUrl;
  };
  const toRegistrySummary = (): ProbeRegistrySummary | undefined => {
    if (!activeRegistry) return undefined;
    return summarizeProbeRegistry(activeRegistry, {
      ...(lastReloadAt ? { lastReloadAt } : {}),
      ...(lastReloadStatus ? { lastReloadStatus } : {}),
      ...(lastReloadError ? { lastReloadError } : {}),
    });
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
    version: serverVersion,
  });

  let shutdownStarted = false;
  let startupComplete = false;
  let parentMonitor: NodeJS.Timeout | undefined;
  const shutdown = (reason: string) => {
    if (shutdownStarted) {
      return;
    }
    shutdownStarted = true;
    if (registryReloadTimer) clearTimeout(registryReloadTimer);
    registryWatch?.close();
    if (parentMonitor) clearInterval(parentMonitor);
    console.error(`mcp-java-dev-tools shutdown: ${reason}`);
    setImmediate(() => process.exit(0));
  };

  const parentPid =
    typeof process.ppid === "number" && Number.isInteger(process.ppid) && process.ppid > 1
      ? process.ppid
      : undefined;
  if (process.stdin && typeof process.stdin.on === "function") {
    process.stdin.on("end", () => {
      if (startupComplete) {
        shutdown("stdin_end");
      }
    });
    process.stdin.on("close", () => {
      if (startupComplete) {
        shutdown("stdin_close");
      }
    });
  }
  process.on("disconnect", () => shutdown("ipc_disconnect"));
  process.on("SIGINT", () => shutdown("sigint"));
  process.on("SIGTERM", () => shutdown("sigterm"));
  if (typeof parentPid === "number") {
    parentMonitor = setInterval(() => {
      try {
        process.kill(parentPid, 0);
      } catch {
        shutdown("parent_exit");
      }
    }, 2_000);
    parentMonitor.unref();
  }

  server.registerResource(
    "status",
    "mcp-java-dev-tools://status",
    { mimeType: "application/json", description: "Server status and defaults" },
    async () => {
      const payload = {
        ok: true,
        name: "mcp-java-dev-tools",
        version: serverVersion,
        buildFingerprint,
        pid: process.pid,
        ...(typeof parentPid === "number" ? { ppid: parentPid } : {}),
        workspaceRoot: cfg.workspaceRootAbs,
        workspaceRootSource: cfg.workspaceRootSource,
        probe: {
          baseUrl: currentBaseUrl(),
          statusPath: probeStatusPath,
          resetPath: probeResetPath,
          actuatePath: probeActuatePath,
          capturePath: probeCapturePath,
          profilerPath: probeProfilerPath,
          waitMaxRetriesDefault: cfg.probeWaitMaxRetries,
          waitUnreachableRetryEnabled: cfg.probeWaitUnreachableRetryEnabled,
          waitUnreachableMaxRetries: cfg.probeWaitUnreachableMaxRetries,
          ...(activeRegistry
            ? {
                activeProfile: activeRegistry.activeProfile,
                profileSource: activeRegistry.profileSource,
                ...(activeRegistry.implicitProbeId ? { implicitProbeId: activeRegistry.implicitProbeId } : {}),
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
        version: serverVersion,
        buildFingerprint,
        pid: process.pid,
        ...(typeof parentPid === "number" ? { ppid: parentPid } : {}),
        workspaceRoot: cfg.workspaceRootAbs,
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    },
  );

  registerRouteSynthesisTool(server, {
    config: cfg,
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
    probeProfilerPath,
    probeWaitMaxRetries: cfg.probeWaitMaxRetries,
    probeWaitUnreachableRetryEnabled: cfg.probeWaitUnreachableRetryEnabled,
    probeWaitUnreachableMaxRetries: cfg.probeWaitUnreachableMaxRetries,
    getProbeRegistry: () => activeRegistry,
  });
  registerTransportExecuteTool(server, {
    allowNonWrappedExecutable: () => activeRegistry?.allowNonWrappedExecutable ?? false,
  });
  registerExecutionProfileExportTool(server, {
    workspaceRootAbs: cfg.workspaceRootAbs,
  });
  registerArtifactManagementTool(server, {
    workspaceRootAbs: cfg.workspaceRootAbs,
    getProbeRegistrySummary: () => toRegistrySummary(),
    reloadProbeRegistry: () => reloadRegistry(),
  });
  registerExecutionOrchestrationTool(server, {
    workspaceRootAbs: cfg.workspaceRootAbs,
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
  startupComplete = true;
  setupRegistryWatcher();
  console.error(
    `mcp-java-dev-tools ${serverVersion} running (stdio). workspaceRoot=${cfg.workspaceRootAbs} probeBaseUrl=${currentBaseUrl()} build=${buildFingerprint} pid=${process.pid}${typeof parentPid === "number" ? ` ppid=${parentPid}` : ""}`,
  );
}

main().catch((err) => {
  console.error("Server error:", err);
  process.exit(1);
});
