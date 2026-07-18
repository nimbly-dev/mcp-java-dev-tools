import * as path from "node:path";
import * as fs from "node:fs";

import { CliArgs } from "@/config/cli-args";
import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";
import { MCP_ENV, type McpEnvVar } from "@/config/env-vars";
import { loadProbeRegistry, type ProbeRegistry } from "@tools-core/probe-registry";
import type { ServerConfig } from "@tools-core/server_config.model";
export type { ServerConfig } from "@tools-core/server_config.model";

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

    const explicitWorkspaceRoot = argWorkspaceRoot ?? envWorkspaceRoot ?? sessionWorkspaceRoot;
    const initialWorkspaceRoot =
      explicitWorkspaceRoot ?? this.detectCanonicalWorkspaceRoot(cwdWorkspaceRoot);
    const initialWorkspaceRootSource: Exclude<ServerConfig["workspaceRootSource"], "probe-config"> =
      argWorkspaceRoot
        ? "arg"
        : envWorkspaceRoot
          ? "env"
          : sessionWorkspaceRoot
            ? "session"
            : initialWorkspaceRoot
              ? "cwd"
              : "missing";

    const initialWorkspaceRootAbs = initialWorkspaceRoot
      ? this.normalizeWorkspaceRoot(initialWorkspaceRoot)
      : undefined;
    const envProbeConfigFile = this.env(MCP_ENV.PROBE_CONFIG_FILE);
    const explicitEnvProbeConfigFile = this.resolveExplicitProbeConfigFile({
      rawValue: envProbeConfigFile,
      workspaceRootAbs: initialWorkspaceRootAbs,
    });
    const detectedProbeConfigFile = initialWorkspaceRootAbs
      ? this.detectWorkspaceProbeConfigFile(initialWorkspaceRootAbs)
      : undefined;
    const probeConfigFile = explicitEnvProbeConfigFile ?? detectedProbeConfigFile;
    const workspaceRootAbs = probeConfigFile
      ? this.isCanonicalProbeConfigFile(probeConfigFile)
        ? this.deriveWorkspaceRootFromProbeConfig(probeConfigFile)
        : initialWorkspaceRootAbs
      : initialWorkspaceRootAbs;
    const workspaceRootSource: ServerConfig["workspaceRootSource"] = probeConfigFile
      ? "probe-config"
      : initialWorkspaceRootSource;
    const probeRegistry =
      typeof probeConfigFile === "string" &&
      probeConfigFile.trim().length > 0 &&
      typeof workspaceRootAbs === "string"
        ? loadProbeRegistry({
            filePath: probeConfigFile.trim(),
            workspaceRootAbs: workspaceRootAbs ?? path.dirname(probeConfigFile),
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
    workspaceRootAbs: string | undefined;
  }): string | undefined {
    if (typeof args.rawValue !== "string" || args.rawValue.trim().length === 0) return undefined;
    const trimmed = args.rawValue.trim();
    if (trimmed === ".mcpjvm/probe-config.json" || trimmed === ".mcpjvm\\probe-config.json") {
      return args.workspaceRootAbs
        ? path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json")
        : undefined;
    }
    if (trimmed === "/.mcpjvm/probe-config.json" || trimmed === "\\.mcpjvm\\probe-config.json") {
      return args.workspaceRootAbs
        ? path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json")
        : undefined;
    }
    return path.resolve(trimmed);
  }

  private deriveWorkspaceRootFromProbeConfig(configFileAbs: string): string {
    const normalizedConfigFileAbs = path.resolve(configFileAbs);
    const configDirectory = path.dirname(normalizedConfigFileAbs);
    if (
      path.basename(normalizedConfigFileAbs).toLowerCase() !== "probe-config.json" ||
      path.basename(configDirectory).toLowerCase() !== ".mcpjvm"
    ) {
      throw new Error(
        `probe_config_location_invalid: probe-config.json must be located at <workspaceRoot>/.mcpjvm/probe-config.json (received ${normalizedConfigFileAbs}).`,
      );
    }
    return path.dirname(configDirectory);
  }

  private isCanonicalProbeConfigFile(configFileAbs: string): boolean {
    const normalizedConfigFileAbs = path.resolve(configFileAbs);
    const configDirectory = path.dirname(normalizedConfigFileAbs);
    return (
      path.basename(normalizedConfigFileAbs).toLowerCase() === "probe-config.json" &&
      path.basename(configDirectory).toLowerCase() === ".mcpjvm"
    );
  }

  private registryImplicitBaseUrl(registry?: ProbeRegistry): string | undefined {
    if (!registry) return undefined;
    if (!registry.implicitProbeId) return undefined;
    const probe = registry.probesById.get(registry.implicitProbeId);
    return probe?.baseUrl;
  }

  private detectWorkspaceProbeConfigFile(workspaceRootAbs: string): string | undefined {
    const candidate = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
    return fs.existsSync(candidate) ? candidate : undefined;
  }

  private detectCanonicalWorkspaceRoot(candidate: string): string | undefined {
    return this.detectWorkspaceProbeConfigFile(candidate) ? candidate : undefined;
  }

  private normalizeWorkspaceRoot(candidate: string): string {
    const resolved = path.resolve(candidate);
    return path.basename(resolved).toLowerCase() === ".mcpjvm" ? path.dirname(resolved) : resolved;
  }
}

export function loadConfigFromEnvAndArgs(argv: string[]): ServerConfig {
  return new ServerConfigLoader(argv).load();
}
