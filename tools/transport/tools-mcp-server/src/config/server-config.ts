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
    const sessionWorkspaceRoot = this.detectSessionWorkspaceRoot();
    const cwdWorkspaceRoot = process.cwd();

    const workspaceRoot =
      argWorkspaceRoot ?? sessionWorkspaceRoot ?? cwdWorkspaceRoot;
    const workspaceRootSource: ServerConfig["workspaceRootSource"] = argWorkspaceRoot
      ? "arg"
      : sessionWorkspaceRoot
        ? "session"
        : "cwd";

    const probeConfigFile = this.detectWorkspaceProbeConfigFile(path.resolve(workspaceRoot));
    const probeRegistry =
      typeof probeConfigFile === "string" && probeConfigFile.trim().length > 0
        ? loadProbeRegistry({
            filePath: probeConfigFile.trim(),
            workspaceRootAbs: path.resolve(workspaceRoot),
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

    const probeBaseUrl = this.registryDefaultBaseUrl(probeRegistry);
    if (!probeBaseUrl) {
      throw new Error(
        "Missing required probe registry configuration. " +
          "Create .mcpjvm/probe-config.json under the workspace (or a parent directory).",
      );
    }

    this.validateProbeBaseUrl(probeBaseUrl);

    return {
      workspaceRootAbs: path.resolve(workspaceRoot),
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
        `Invalid ${MCP_ENV.PROBE_BASE_URL}='${probeBaseUrl}'. ` +
          "Use full URL format like http://127.0.0.1:9193",
      );
    }
    if (!parsed.port) {
      throw new Error(
        `${MCP_ENV.PROBE_BASE_URL} must include an explicit probe port (example: http://127.0.0.1:9193). ` +
          "If unknown, ask the user which service probe port is currently mapped.",
      );
    }
  }

  private registryDefaultBaseUrl(registry?: ProbeRegistry): string | undefined {
    if (!registry) return undefined;
    const defaultProbe = registry.probesById.get(registry.defaultProbeId);
    return defaultProbe?.baseUrl;
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
