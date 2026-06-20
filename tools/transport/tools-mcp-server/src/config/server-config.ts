import * as path from "node:path";
import * as fs from "node:fs";

import { CliArgs } from "@/config/cli-args";
import { CONFIG_DEFAULTS } from "@/config/defaults";
import { MCP_ENV, type McpEnvVar } from "@/config/env-vars";
import { loadProbeRegistry, type ProbeRegistry } from "@/config/probe-registry";

export type ServerConfig = {
  workspaceRootAbs: string;
  workspaceRootSource: "arg" | "env" | "session" | "cwd";
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeCapturePath: string;
  probeLineSelectionMaxScanLines: number;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  probeRegistry?: ProbeRegistry;
};

export class ServerConfigLoader {
  private readonly args: CliArgs;

  constructor(argv: string[]) {
    this.args = new CliArgs(argv);
  }

  load(): ServerConfig {
    const argWorkspaceRoot = this.args.get("--workspace-root");
    const envWorkspaceRoot = this.env(MCP_ENV.WORKSPACE_ROOT);
    const sessionWorkspaceRoot = this.detectSessionWorkspaceRoot();
    const cwdWorkspaceRoot = process.cwd();

    const workspaceRoot =
      argWorkspaceRoot ?? envWorkspaceRoot ?? sessionWorkspaceRoot ?? cwdWorkspaceRoot;
    const workspaceRootSource: ServerConfig["workspaceRootSource"] = argWorkspaceRoot
      ? "arg"
      : envWorkspaceRoot
        ? "env"
      : sessionWorkspaceRoot
        ? "session"
        : "cwd";

    const workspaceRootAbs = path.resolve(workspaceRoot);
    const detectedProbeConfigFile = this.detectWorkspaceProbeConfigFile(workspaceRootAbs);
    const envProbeConfigFile = this.env(MCP_ENV.PROBE_CONFIG_FILE);
    const explicitEnvProbeConfigFile = this.resolveExplicitProbeConfigFile({
      rawValue: envProbeConfigFile,
      workspaceRootAbs,
    });
    const probeConfigFile = this.selectProbeConfigFile({
      workspaceRootAbs,
      ...(detectedProbeConfigFile ? { detectedProbeConfigFile } : {}),
      ...(explicitEnvProbeConfigFile ? { explicitEnvProbeConfigFile } : {}),
    });
    const probeRegistry =
      typeof probeConfigFile === "string" && probeConfigFile.trim().length > 0
        ? loadProbeRegistry({
            filePath: probeConfigFile.trim(),
            workspaceRootAbs,
          })
        : undefined;

    const probeStatusPath = CONFIG_DEFAULTS.PROBE_STATUS_PATH;
    const probeResetPath = CONFIG_DEFAULTS.PROBE_RESET_PATH;
    const probeCapturePath = CONFIG_DEFAULTS.PROBE_CAPTURE_PATH;
    const probeLineSelectionMaxScanLines = this.parseIntFlag(
      this.env(MCP_ENV.PROBE_LINE_SELECTION_MAX_SCAN_LINES),
      CONFIG_DEFAULTS.PROBE_LINE_SELECTION_MAX_SCAN_LINES,
      CONFIG_DEFAULTS.PROBE_LINE_SELECTION_MAX_SCAN_LINES_MIN,
      CONFIG_DEFAULTS.PROBE_LINE_SELECTION_MAX_SCAN_LINES_MAX,
    );

    const probeWaitMaxRetries = this.parseIntFlag(
      this.args.get("--probe-wait-max-retries") ?? this.env(MCP_ENV.PROBE_WAIT_MAX_RETRIES),
      CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES,
      CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES_MIN,
      CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES_MAX,
    );
    const probeWaitUnreachableRetryEnabled = this.parseBooleanFlag(
      this.env(MCP_ENV.PROBE_WAIT_UNREACHABLE_RETRY_ENABLED),
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_RETRY_ENABLED,
    );
    const probeWaitUnreachableMaxRetries = this.parseIntFlag(
      this.env(MCP_ENV.PROBE_WAIT_UNREACHABLE_MAX_RETRIES),
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES_MIN,
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES_MAX,
    );

    const implicitProbeBaseUrl = this.registryImplicitBaseUrl(probeRegistry);
    const probeBaseUrl = implicitProbeBaseUrl || "";
    if (!probeRegistry) {
      throw new Error(
        "Missing required probe-config.json Probe registry. " +
          "Create .mcpjvm/probe-config.json or set MCP_PROBE_CONFIG_FILE to a valid Probe registry file.",
      );
    }
    if (probeBaseUrl) {
      this.validateProbeBaseUrl(probeBaseUrl);
    }

    return {
      workspaceRootAbs,
      workspaceRootSource,
      probeBaseUrl,
      probeStatusPath,
      probeResetPath,
      probeCapturePath,
      probeLineSelectionMaxScanLines,
      probeWaitMaxRetries,
      probeWaitUnreachableRetryEnabled,
      probeWaitUnreachableMaxRetries,
      ...(probeRegistry ? { probeRegistry } : {}),
    };
  }

  private env(name: McpEnvVar): string | undefined {
    return process.env[name];
  }

  private parseBooleanFlag(raw: string | undefined, defaultValue: boolean): boolean {
    if (typeof raw !== "string") return defaultValue;
    const v = raw.trim().toLowerCase();
    if (v === "1" || v === "true" || v === "yes" || v === "on") return true;
    if (v === "0" || v === "false" || v === "no" || v === "off") return false;
    return defaultValue;
  }

  private parseIntFlag(
    raw: string | undefined,
    defaultValue: number,
    min: number,
    max: number,
  ): number {
    if (typeof raw !== "string") return defaultValue;
    const parsed = Number(raw.trim());
    if (!Number.isFinite(parsed)) return defaultValue;
    const n = Math.trunc(parsed);
    if (n < min) return min;
    if (n > max) return max;
    return n;
  }

  private detectSessionWorkspaceRoot(): string | undefined {
    const candidates = [process.env.INIT_CWD, process.env.PWD];
    for (const c of candidates) {
      if (typeof c === "string" && c.trim().length > 0) {
        return c.trim();
      }
    }
    return undefined;
  }

  private validateProbeBaseUrl(probeBaseUrl: string): void {
    let parsed: URL;
    try {
      parsed = new URL(probeBaseUrl);
    } catch {
      throw new Error(
        `Invalid Probe base URL '${probeBaseUrl}'. Use full URL format like http://127.0.0.1:9193.`,
      );
    }
    if (!parsed.port) {
      throw new Error(
        `Probe base URL must include an explicit probe port (example: http://127.0.0.1:9193). ` +
          "If unknown, ask the user which service probe port is currently mapped.",
      );
    }
  }

  private resolveExplicitProbeConfigFile(args: {
    rawValue: string | undefined;
    workspaceRootAbs: string;
  }): string | undefined {
    if (typeof args.rawValue !== "string" || args.rawValue.trim().length === 0) return undefined;
    const trimmed = args.rawValue.trim();
    if (trimmed === ".mcpjvm/probe-config.json" || trimmed === ".mcpjvm\\probe-config.json") {
      return path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json");
    }
    if (trimmed === "/.mcpjvm/probe-config.json" || trimmed === "\\.mcpjvm\\probe-config.json") {
      return path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json");
    }
    return path.resolve(trimmed);
  }

  private selectProbeConfigFile(args: {
    workspaceRootAbs: string;
    detectedProbeConfigFile?: string;
    explicitEnvProbeConfigFile?: string;
  }): string | undefined {
    if (args.detectedProbeConfigFile) {
      if (
        args.explicitEnvProbeConfigFile &&
        this.isPathWithinWorkspace(args.explicitEnvProbeConfigFile, args.workspaceRootAbs)
      ) {
        return args.explicitEnvProbeConfigFile;
      }
      return args.detectedProbeConfigFile;
    }
    return args.explicitEnvProbeConfigFile;
  }

  private isPathWithinWorkspace(candidateAbs: string, workspaceRootAbs: string): boolean {
    const relative = path.relative(workspaceRootAbs, candidateAbs);
    return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
  }

  private registryImplicitBaseUrl(registry?: ProbeRegistry): string | undefined {
    if (!registry) return undefined;
    if (!registry.implicitProbeId) return undefined;
    const probe = registry.probesById.get(registry.implicitProbeId);
    return probe?.baseUrl;
  }

  private detectWorkspaceProbeConfigFile(workspaceRootAbs: string): string | undefined {
    let cursor = workspaceRootAbs;
    while (true) {
      const candidate = path.join(cursor, ".mcpjvm", "probe-config.json");
      if (fs.existsSync(candidate)) return candidate;
      const parent = path.dirname(cursor);
      if (parent === cursor) break;
      cursor = parent;
    }
    return undefined;
  }
}

export function loadConfigFromEnvAndArgs(argv: string[]): ServerConfig {
  return new ServerConfigLoader(argv).load();
}
