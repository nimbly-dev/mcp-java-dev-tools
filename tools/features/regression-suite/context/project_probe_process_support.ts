/**
 * Probe and process-control support for regression runtime context resolution.
 */
import { promises as fs } from "node:fs";
import { spawn } from "node:child_process";
import net from "node:net";
import path from "node:path";
import { URL } from "node:url";

import type { ProjectWorkspaceEntry } from "@tools-project-artifact-spec/models/project_artifact.model";
import type { ProbeRegistry } from "../models/regression_context.model";

export function extractProbePort(baseUrl: string): number | null {
  try {
    const parsed = new URL(baseUrl);
    const port = Number(parsed.port);
    if (!Number.isInteger(port) || port <= 0) return null;
    return port;
  } catch {
    return null;
  }
}

function stripBom(input: string): string {
  return input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;
}

function parseDotEnvText(input: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const rawLine of input.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = line.slice(eq + 1);
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    }
    env[key] = value;
  }
  return env;
}

export async function readWorkspaceEnvFile(args: {
  workspace: ProjectWorkspaceEntry;
  workspaceRootAbs: string;
}): Promise<Record<string, string>> {
  if (!args.workspace.envFile) return {};
  const envFileAbs = path.isAbsolute(args.workspace.envFile)
    ? args.workspace.envFile
    : path.resolve(args.workspaceRootAbs, args.workspace.envFile);
  try {
    return parseDotEnvText(await fs.readFile(envFileAbs, "utf8"));
  } catch {
    return {};
  }
}

export function resolveWorkspaceEnvFileAbs(args: {
  workspace: ProjectWorkspaceEntry;
  workspaceRootAbs: string;
}): string | null {
  if (!args.workspace.envFile) return null;
  return path.isAbsolute(args.workspace.envFile)
    ? args.workspace.envFile
    : path.resolve(args.workspaceRootAbs, args.workspace.envFile);
}

export function resolveWorkspaceRequestTimeoutMs(
  workspace: ProjectWorkspaceEntry,
  fallbackMs: number,
): number {
  const timeoutRaw = workspace.defaults?.requestTimeoutMs;
  if (typeof timeoutRaw === "number" && Number.isFinite(timeoutRaw) && timeoutRaw > 0) {
    return Math.floor(timeoutRaw);
  }
  return fallbackMs;
}

export function resolveWorkspaceRetryMax(
  workspace: ProjectWorkspaceEntry,
  fallback: number,
): number {
  const retryMaxRaw = workspace.defaults?.retryMax;
  if (typeof retryMaxRaw === "number" && Number.isFinite(retryMaxRaw) && retryMaxRaw > 0) {
    return Math.floor(retryMaxRaw);
  }
  return fallback;
}

export async function readProbeRegistryFromWorkspace(workspaceRootAbs: string): Promise<
  | {
      ok: true;
      registry: ProbeRegistry;
      profileName: string;
    }
  | {
      ok: false;
      detail: string;
    }
> {
  const registryPath = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  let raw: string;
  try {
    raw = await fs.readFile(registryPath, "utf8");
  } catch {
    return {
      ok: false,
      detail: `Probe registry not found at ${registryPath}`,
    };
  }
  let parsed: ProbeRegistry;
  try {
    parsed = JSON.parse(stripBom(raw)) as ProbeRegistry;
  } catch {
    return {
      ok: false,
      detail: `Probe registry JSON is invalid at ${registryPath}`,
    };
  }
  const workspaces = Array.isArray(parsed.workspaces) ? parsed.workspaces : [];
  const workspaceMatch = workspaces.find((entry) => entry.root === workspaceRootAbs);
  const profileName = workspaceMatch?.profile ?? parsed.defaultProfile;
  if (!profileName || !parsed.profiles || !parsed.profiles[profileName]) {
    return {
      ok: false,
      detail: `Probe profile could not be resolved for workspace ${workspaceRootAbs}`,
    };
  }
  return {
    ok: true,
    registry: parsed,
    profileName,
  };
}

export function resolveProbeBaseUrlFromRegistry(args: {
  registry: ProbeRegistry;
  profileName: string;
  probeId: string;
}): string | null {
  const profile = args.registry.profiles?.[args.profileName];
  const probe = profile?.probes?.[args.probeId];
  const baseUrl = typeof probe?.baseUrl === "string" ? probe.baseUrl.trim() : "";
  return baseUrl.length > 0 ? baseUrl : null;
}

export function getAgentJarPathForAutoStart(): string | null {
  const configured =
    process.env.MCP_JAVA_AGENT_JAR ??
    process.env.MCP_PROBE_JAVA_AGENT_JAR ??
    process.env.MCP_AGENT_JAR_PATH;
  if (!configured || configured.trim().length === 0) return null;
  return configured.trim();
}

export function buildProbeJavaAgentArg(args: {
  serviceName: string;
  profileName: string;
  registry: ProbeRegistry;
}): { ok: true; agentArg: string; probeBaseUrl: string } | { ok: false; detail: string } {
  const profile = args.registry.profiles?.[args.profileName];
  const probe = profile?.probes?.[args.serviceName];
  if (!probe) {
    return {
      ok: false,
      detail: `Probe registry entry missing for startup '${args.serviceName}' in profile '${args.profileName}'.`,
    };
  }
  const baseUrl = typeof probe.baseUrl === "string" ? probe.baseUrl.trim() : "";
  const port = baseUrl ? extractProbePort(baseUrl) : null;
  if (!port) {
    return {
      ok: false,
      detail: `Probe baseUrl missing/invalid for startup '${args.serviceName}' in profile '${args.profileName}'.`,
    };
  }
  const include = Array.isArray(probe.include)
    ? probe.include.filter((entry) => typeof entry === "string" && entry.trim().length > 0)
    : [];
  if (include.length === 0) {
    return {
      ok: false,
      detail: `Probe include[] missing for startup '${args.serviceName}' in profile '${args.profileName}'.`,
    };
  }
  const agentJar = getAgentJarPathForAutoStart();
  if (!agentJar) {
    return {
      ok: false,
      detail:
        "Auto-start probe injection requires MCP_JAVA_AGENT_JAR (or MCP_PROBE_JAVA_AGENT_JAR) to be set.",
    };
  }
  return {
    ok: true,
    agentArg: `-javaagent:${agentJar}=host=0.0.0.0;port=${port};include=${include.join(",")}`,
    probeBaseUrl: baseUrl,
  };
}

export async function tcpCheck(target: string, timeoutMs: number): Promise<boolean> {
  const [host, portStr] = target.split(":");
  const port = Number(portStr);
  if (!host || !Number.isInteger(port) || port <= 0) return false;
  return await new Promise<boolean>((resolve) => {
    const socket = new net.Socket();
    let done = false;
    const end = (ok: boolean) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs, () => end(false));
    socket.once("error", () => end(false));
    socket.connect(port, host, () => end(true));
  });
}

export async function httpCheck(
  urlRaw: string,
  method: string,
  timeoutMs: number,
  expectStatus?: number,
): Promise<boolean> {
  try {
    const url = new URL(urlRaw);
    const ctrl = new AbortController();
    const handle = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const response = await fetch(url, { method, signal: ctrl.signal });
      if (typeof expectStatus === "number") return response.status === expectStatus;
      return response.status >= 200 && response.status <= 399;
    } finally {
      clearTimeout(handle);
    }
  } catch {
    return false;
  }
}

export function extractServerPortFromStartupArgs(args: string[] | undefined): number | null {
  if (!Array.isArray(args)) return null;
  for (const raw of args) {
    if (typeof raw !== "string") continue;
    const match = raw.match(/^--server\.port=(\d{2,5})$/);
    if (!match) continue;
    const port = Number(match[1]);
    if (Number.isInteger(port) && port > 0 && port <= 65535) return port;
  }
  return null;
}

export async function isPortOpen(host: string, port: number, timeoutMs = 1200): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const socket = new net.Socket();
    let done = false;
    const end = (ok: boolean) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs, () => end(false));
    socket.once("error", () => end(false));
    socket.connect(port, host, () => end(true));
  });
}

export async function findPidListeningOnPortWindows(port: number): Promise<number | null> {
  return await new Promise<number | null>((resolve) => {
    const child = spawn("netstat", ["-ano", "-p", "tcp"], { windowsHide: true });
    let stdout = "";
    child.stdout.on("data", (buf) => {
      stdout += String(buf ?? "");
    });
    child.on("close", () => {
      const lines = stdout.split(/\r?\n/);
      for (const line of lines) {
        if (!line.includes(`:${port}`)) continue;
        if (!line.toUpperCase().includes("LISTENING")) continue;
        const parts = line.trim().split(/\s+/);
        const pidRaw = parts[parts.length - 1];
        const pid = Number(pidRaw);
        if (Number.isInteger(pid) && pid > 0) {
          resolve(pid);
          return;
        }
      }
      resolve(null);
    });
    child.on("error", () => resolve(null));
  });
}

export async function killProcessByPidWindows(pid: number): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const child = spawn("taskkill", ["/PID", String(pid), "/F"], { windowsHide: true });
    child.on("close", (code) => resolve(code === 0));
    child.on("error", () => resolve(false));
  });
}
