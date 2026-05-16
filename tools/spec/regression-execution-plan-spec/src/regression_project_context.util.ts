import net from "node:net";
import { spawn } from "node:child_process";
import { promises as fs } from "node:fs";
import path from "node:path";
import { URL } from "node:url";

import type {
  ProjectRuntimeContext,
  ProjectWorkspaceEntry,
  RunPrerequisite,
} from "@tools-project-artifact-spec/models/project_artifact.model";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";

type ProjectContextBlockedReason =
  | "project_artifact_missing"
  | "project_artifact_invalid"
  | "workspace_root_invalid"
  | "env_key_missing"
  | "runtime_context_unknown"
  | "external_system_invalid"
  | "external_healthcheck_failed";

export type ProjectContextResolutionResult =
  | {
      status: "ok";
      contextPatch: Record<string, unknown>;
      runtimeContextName?: string;
    }
  | {
      status: "blocked";
      reasonCode: ProjectContextBlockedReason;
      missing?: string[];
      checks?: string[];
      nextAction?: string;
      requiredUserAction: string[];
    };

type ResolveProjectContextArgs = {
  workspaceRootAbs: string;
  projectsFileAbs: string;
  env?: Record<string, string | undefined>;
  runtimeContextName?: string;
  defaultsOverride?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
  healthChecksEnabled?: boolean;
  strictProbeVerification?: boolean;
  strictProbeBaseUrls?: string[];
  runtimeStarter?: RuntimeStarter;
};

type RuntimeStartResult = {
  attempted: boolean;
  success: boolean;
  detail?: string;
};

type RuntimeStarter = (args: {
  runtimeContext: ProjectRuntimeContext;
  workspaceRootAbs: string;
}) => Promise<RuntimeStartResult>;

type ProbeRegistry = {
  defaultProfile?: string;
  profiles?: Record<
    string,
    {
      probes?: Record<
        string,
        {
          include?: string[];
          baseUrl?: string;
        }
      >;
    }
  >;
  workspaces?: Array<{
    root?: string;
    profile?: string;
  }>;
};

function extractProbePort(baseUrl: string): number | null {
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

async function readProbeRegistryFromWorkspace(workspaceRootAbs: string): Promise<{
  ok: true;
  registry: ProbeRegistry;
  profileName: string;
} | {
  ok: false;
  detail: string;
}> {
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

function resolveProbeBaseUrlFromRegistry(args: {
  registry: ProbeRegistry;
  profileName: string;
  probeId: string;
}): string | null {
  const profile = args.registry.profiles?.[args.profileName];
  const probe = profile?.probes?.[args.probeId];
  const baseUrl = typeof probe?.baseUrl === "string" ? probe.baseUrl.trim() : "";
  return baseUrl.length > 0 ? baseUrl : null;
}

function getAgentJarPathForAutoStart(): string | null {
  const configured =
    process.env.MCP_JAVA_AGENT_JAR ??
    process.env.MCP_PROBE_JAVA_AGENT_JAR ??
    process.env.MCP_AGENT_JAR_PATH;
  if (!configured || configured.trim().length === 0) return null;
  return configured.trim();
}

function buildProbeJavaAgentArg(args: {
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
  const include = Array.isArray(probe.include) ? probe.include.filter((entry) => typeof entry === "string" && entry.trim().length > 0) : [];
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
      detail: "Auto-start probe injection requires MCP_JAVA_AGENT_JAR (or MCP_PROBE_JAVA_AGENT_JAR) to be set.",
    };
  }
  return {
    ok: true,
    agentArg: `-javaagent:${agentJar}=host=0.0.0.0;port=${port};include=${include.join(",")}`,
    probeBaseUrl: baseUrl,
  };
}

async function tcpCheck(target: string, timeoutMs: number): Promise<boolean> {
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

async function httpCheck(urlRaw: string, method: string, timeoutMs: number, expectStatus?: number): Promise<boolean> {
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

function extractServerPortFromStartupArgs(args: string[] | undefined): number | null {
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

async function isPortOpen(host: string, port: number, timeoutMs = 1200): Promise<boolean> {
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

async function findPidListeningOnPortWindows(port: number): Promise<number | null> {
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

async function killProcessByPidWindows(pid: number): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const child = spawn("taskkill", ["/PID", String(pid), "/F"], { windowsHide: true });
    child.on("close", (code) => resolve(code === 0));
    child.on("error", () => resolve(false));
  });
}

function selectWorkspace(
  workspaces: ProjectWorkspaceEntry[],
  workspaceRootAbs: string,
): ProjectWorkspaceEntry | null {
  for (const workspace of workspaces) {
    if (workspace.projectRoot === workspaceRootAbs) return workspace;
  }
  return null;
}

async function runRequiredHealthChecks(workspace: ProjectWorkspaceEntry): Promise<{
  ok: true;
  checks: string[];
} | {
  ok: false;
  checks: string[];
  failures: string[];
  nextAction: string;
  requiredUserAction: string[];
}> {
  const retryMaxRaw = workspace.defaults?.retryMax;
  const retryMax =
    typeof retryMaxRaw === "number" && Number.isFinite(retryMaxRaw) && retryMaxRaw > 0
      ? Math.floor(retryMaxRaw)
      : 1;
  const timeoutDefaultRaw = workspace.defaults?.requestTimeoutMs;
  const timeoutDefaultMs =
    typeof timeoutDefaultRaw === "number" && Number.isFinite(timeoutDefaultRaw) && timeoutDefaultRaw > 0
      ? Math.floor(timeoutDefaultRaw)
      : 3000;
  const systems = workspace.externalSystems ?? [];
  const failures: string[] = [];
  const checks: string[] = [];
  for (const system of systems) {
    for (const check of system.healthChecks ?? []) {
      const required = check.required === true;
      if (!required) continue;
      const timeoutMs = typeof check.timeoutMs === "number" ? check.timeoutMs : timeoutDefaultMs;
      let ok = false;
      for (let attempt = 1; attempt <= retryMax; attempt += 1) {
        if (check.type === "tcp") {
          ok = await tcpCheck(check.target, timeoutMs);
        } else {
          ok = await httpCheck(
            check.url,
            check.method ?? "GET",
            timeoutMs,
            check.expect?.status,
          );
        }
        if (ok) break;
      }
      checks.push(`${system.name}:${check.id}=${ok ? "ready" : "unreachable"}`);
      if (!ok) failures.push(`${system.name}:${check.id}`);
    }
  }
  if (failures.length > 0) {
    return {
      checks,
      failures,
      nextAction: `Ensure services are running or update .env/runtime config for: ${failures.join(", ")}.`,
      ok: false,
      requiredUserAction: [`External health checks failed: ${failures.join(", ")}`],
    };
  }
  return { ok: true, checks };
}

function buildHealthcheckKeyFromRunPrerequisite(prereq: RunPrerequisite): string | null {
  if (prereq.type !== "assert" || !prereq.assert) return null;
  if (prereq.assert.kind === "port_reachable" && prereq.assert.host && prereq.assert.port) {
    return `tcp:${prereq.assert.host}:${prereq.assert.port}`;
  }
  if (prereq.assert.kind === "url_reachable" && prereq.assert.url) {
    return `http:${prereq.assert.url}`;
  }
  return null;
}

function buildHealthcheckKey(check: { type: "tcp" | "http"; target?: string; url?: string }): string | null {
  if (check.type === "tcp" && typeof check.target === "string") return `tcp:${check.target}`;
  if (check.type === "http" && typeof check.url === "string") return `http:${check.url}`;
  return null;
}

async function runRequiredHealthChecksWithDedupe(args: {
  workspace: ProjectWorkspaceEntry;
  skipKeys: Set<string>;
}): Promise<{
  ok: true;
  checks: string[];
} | {
  ok: false;
  checks: string[];
  failures: string[];
  nextAction: string;
  requiredUserAction: string[];
}> {
  const retryMaxRaw = args.workspace.defaults?.retryMax;
  const retryMax =
    typeof retryMaxRaw === "number" && Number.isFinite(retryMaxRaw) && retryMaxRaw > 0
      ? Math.floor(retryMaxRaw)
      : 1;
  const timeoutDefaultRaw = args.workspace.defaults?.requestTimeoutMs;
  const timeoutDefaultMs =
    typeof timeoutDefaultRaw === "number" && Number.isFinite(timeoutDefaultRaw) && timeoutDefaultRaw > 0
      ? Math.floor(timeoutDefaultRaw)
      : 3000;
  const systems = args.workspace.externalSystems ?? [];
  const failures: string[] = [];
  const checks: string[] = [];
  for (const system of systems) {
    for (const check of system.healthChecks ?? []) {
      const required = check.required === true;
      if (!required) continue;
      const dedupeKey = buildHealthcheckKey(check);
      if (dedupeKey && args.skipKeys.has(dedupeKey)) {
        checks.push(`${system.name}:${check.id}=covered_by_run_prerequisite`);
        continue;
      }
      const timeoutMs = typeof check.timeoutMs === "number" ? check.timeoutMs : timeoutDefaultMs;
      let ok = false;
      for (let attempt = 1; attempt <= retryMax; attempt += 1) {
        if (check.type === "tcp") {
          ok = await tcpCheck(check.target, timeoutMs);
        } else {
          ok = await httpCheck(
            check.url,
            check.method ?? "GET",
            timeoutMs,
            check.expect?.status,
          );
        }
        if (ok) break;
      }
      checks.push(`${system.name}:${check.id}=${ok ? "ready" : "unreachable"}`);
      if (!ok) failures.push(`${system.name}:${check.id}`);
    }
  }
  if (failures.length > 0) {
    return {
      checks,
      failures,
      nextAction: `Ensure services are running or update .env/runtime config for: ${failures.join(", ")}.`,
      ok: false,
      requiredUserAction: [`External health checks failed: ${failures.join(", ")}`],
    };
  }
  return { ok: true, checks };
}

async function isCommandAvailable(commandName: string): Promise<boolean> {
  const bin = process.platform === "win32" ? "where" : "which";
  return await new Promise<boolean>((resolve) => {
    const child = spawn(bin, [commandName], { windowsHide: true });
    child.on("close", (code) => resolve(code === 0));
    child.on("error", () => resolve(false));
  });
}

async function executeRunPrerequisites(args: {
  workspace: ProjectWorkspaceEntry;
  workspaceRootAbs: string;
  env: Record<string, string | undefined>;
  contextPatch: Record<string, unknown>;
}): Promise<
  | { status: "ok"; checks: string[]; dedupeKeys: Set<string> }
  | {
      status: "blocked";
      reasonCode: ProjectContextBlockedReason;
      checks: string[];
      nextAction: string;
      requiredUserAction: string[];
    }
> {
  const prereqs = [...(args.workspace.runPrerequisites ?? [])].sort((a, b) => a.order - b.order);
  if (prereqs.length === 0) return { status: "ok", checks: [], dedupeKeys: new Set<string>() };
  const checks: string[] = [];
  const dedupeKeys = new Set<string>();
  for (const prereq of prereqs) {
    if (prereq.type === "assert" && prereq.assert) {
      let ok = false;
      if (prereq.assert.kind === "env_exists") {
        const val = prereq.assert.key ? args.env[prereq.assert.key] : undefined;
        ok = typeof val === "string" && val.trim().length > 0;
      } else if (prereq.assert.kind === "context_exists") {
        const val = prereq.assert.key ? args.contextPatch[prereq.assert.key] : undefined;
        ok = typeof val !== "undefined" && val !== null && String(val).trim().length > 0;
      } else if (prereq.assert.kind === "file_exists") {
        const rel = prereq.assert.path ?? "";
        const abs = path.isAbsolute(rel) ? rel : path.resolve(args.workspaceRootAbs, rel);
        try {
          const stat = await fs.stat(abs);
          ok = stat.isFile();
        } catch {
          ok = false;
        }
      } else if (prereq.assert.kind === "port_reachable") {
        ok = await tcpCheck(`${prereq.assert.host}:${prereq.assert.port}`, prereq.assert.timeoutMs ?? 3000);
      } else if (prereq.assert.kind === "url_reachable") {
        ok = await httpCheck(prereq.assert.url ?? "", "GET", prereq.assert.timeoutMs ?? 3000);
      } else if (prereq.assert.kind === "command_available") {
        ok = await isCommandAvailable(prereq.assert.name ?? "");
      }
      checks.push(`run_prereq:${prereq.id}=${ok ? "pass" : "fail"}`);
      if (ok) {
        const key = buildHealthcheckKeyFromRunPrerequisite(prereq);
        if (key) dedupeKeys.add(key);
        continue;
      }
      if (prereq.onFail === "skip_remaining") {
        checks.push(`run_prereq:${prereq.id}=skip_remaining`);
        break;
      }
      return {
        status: "blocked",
        reasonCode: "external_healthcheck_failed",
        checks,
        nextAction: `Fix run prerequisite '${prereq.id}' and retry.`,
        requiredUserAction: [`Run prerequisite '${prereq.id}' failed.`],
      };
    }
    if (prereq.type === "script" && prereq.script) {
      const script = prereq.script;
      const scriptAbs = path.isAbsolute(script.scriptPath)
        ? script.scriptPath
        : path.resolve(args.workspaceRootAbs, script.scriptPath);
      const cwd = script.cwd
        ? (path.isAbsolute(script.cwd) ? script.cwd : path.resolve(args.workspaceRootAbs, script.cwd))
        : args.workspaceRootAbs;
      const timeoutMs = typeof script.timeoutMs === "number" && script.timeoutMs > 0 ? script.timeoutMs : 120000;
      let command = "";
      let cmdArgs: string[] = [];
      if (script.command === "node") {
        command = "node";
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else if (script.command === "python") {
        command = "python";
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else if (script.command === "sh") {
        command = "sh";
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else {
        command = "powershell";
        cmdArgs = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptAbs, ...(script.args ?? [])];
      }
      const result = await new Promise<{ ok: boolean; detail: string }>((resolve) => {
        const child = spawn(command, cmdArgs, { cwd, env: process.env, windowsHide: true });
        let stderr = "";
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) return;
          settled = true;
          child.kill();
          resolve({ ok: false, detail: `timeout (${timeoutMs}ms)` });
        }, timeoutMs);
        child.stderr.on("data", (buf) => {
          stderr += String(buf ?? "");
        });
        child.on("close", (code) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve({
            ok: code === 0,
            detail: code === 0 ? "ok" : (stderr.trim() || `exit_code=${String(code ?? 1)}`),
          });
        });
        child.on("error", (err) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve({ ok: false, detail: String(err.message ?? err) });
        });
      });
      checks.push(`run_prereq:${prereq.id}=${result.ok ? "pass" : `fail(${result.detail})`}`);
      if (result.ok) continue;
      if (prereq.onFail === "skip_remaining") {
        checks.push(`run_prereq:${prereq.id}=skip_remaining`);
        break;
      }
      return {
        status: "blocked",
        reasonCode: "external_healthcheck_failed",
        checks,
        nextAction: `Fix run prerequisite script '${prereq.id}' and retry.`,
        requiredUserAction: [`Run prerequisite script '${prereq.id}' failed: ${result.detail}`],
      };
    }
  }
  return { status: "ok", checks, dedupeKeys };
}

async function defaultRuntimeStarter(args: {
  runtimeContext: ProjectRuntimeContext;
  workspaceRootAbs: string;
}): Promise<RuntimeStartResult> {
  const { runtimeContext, workspaceRootAbs } = args;
  if (runtimeContext.mode === "terminal") {
    const startups = runtimeContext.startups ?? [];
    if (startups.length === 0) {
      return {
        attempted: true,
        success: false,
        detail: "Terminal runtime auto-start requires runtimeContexts[].startups[].",
      };
    }
    const registry = await readProbeRegistryFromWorkspace(workspaceRootAbs);
    if (!registry.ok) {
      return {
        attempted: true,
        success: false,
        detail: registry.detail,
      };
    }
    const started: string[] = [];
    const startedProbeBaseUrls: Array<{ name: string; baseUrl: string }> = [];
    for (const startup of startups) {
      const agent = buildProbeJavaAgentArg({
        serviceName: startup.name,
        profileName: registry.profileName,
        registry: registry.registry,
      });
      if (!agent.ok) {
        return {
          attempted: true,
          success: false,
          detail: agent.detail,
        };
      }
      const existingToolOptions = typeof startup.env?.JAVA_TOOL_OPTIONS === "string"
        ? startup.env.JAVA_TOOL_OPTIONS.trim()
        : "";
      const javaToolOptions = existingToolOptions.length > 0
        ? `${agent.agentArg} ${existingToolOptions}`
        : agent.agentArg;
      const cwd = startup.appdir
        ? (path.isAbsolute(startup.appdir)
            ? startup.appdir
            : path.resolve(workspaceRootAbs, startup.appdir))
        : workspaceRootAbs;
      const apiPort = extractServerPortFromStartupArgs(startup.args);
      if (apiPort) {
        const apiUp = await isPortOpen("127.0.0.1", apiPort);
        const probePort = extractProbePort(agent.probeBaseUrl);
        const probeUp = probePort ? await isPortOpen("127.0.0.1", probePort) : false;
        if (apiUp && !probeUp && process.platform === "win32") {
          const pid = await findPidListeningOnPortWindows(apiPort);
          if (pid) {
            await killProcessByPidWindows(pid);
            await new Promise((resolve) => setTimeout(resolve, 600));
          }
        }
      }
      const isJavaCommand = /(^|\\|\/)java(\.exe)?$/i.test(startup.command.trim());
      const baseArgs = startup.args ?? [];
      const hasJavaAgentArg = baseArgs.some((arg) => typeof arg === "string" && arg.trim().startsWith("-javaagent:"));
      const commandArgs =
        isJavaCommand && !hasJavaAgentArg
          ? [agent.agentArg, ...baseArgs]
          : baseArgs;
      try {
        const child = spawn(startup.command, commandArgs, {
          cwd,
          env: {
            ...process.env,
            ...(startup.env ?? {}),
            JAVA_TOOL_OPTIONS: javaToolOptions,
          },
          windowsHide: true,
          detached: true,
          stdio: "ignore",
        });
        child.unref();
        started.push(startup.name);
        startedProbeBaseUrls.push({ name: startup.name, baseUrl: agent.probeBaseUrl });
      } catch (error) {
        return {
          attempted: true,
          success: false,
          detail: `Terminal runtime start failed for '${startup.name}': ${error instanceof Error ? error.message : String(error)}`,
        };
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1200));
    for (const entry of startedProbeBaseUrls) {
      const ok = await httpCheck(`${entry.baseUrl.replace(/\/$/, "")}/__probe/status`, "GET", 2000);
      if (!ok) {
        return {
          attempted: true,
          success: false,
          detail: `Probe listener not reachable after startup for '${entry.name}' at ${entry.baseUrl}.`,
        };
      }
    }
    return {
      attempted: true,
      success: true,
      detail: `Started terminal runtime apps: ${started.join(", ")}`,
    };
  }
  if (runtimeContext.mode !== "docker") {
    return {
      attempted: false,
      success: false,
      detail: `Runtime mode '${runtimeContext.mode}' does not have auto-start command wiring in v1.`,
    };
  }
  if (!runtimeContext.composeFile) {
    return {
      attempted: true,
      success: false,
      detail: "Docker runtime context requires composeFile for auto-start.",
    };
  }
  const composeFileAbs = path.isAbsolute(runtimeContext.composeFile)
    ? runtimeContext.composeFile
    : path.resolve(workspaceRootAbs, runtimeContext.composeFile);
  const command = "docker";
  const cmdArgs = ["compose", "-f", composeFileAbs, "up", "-d"];
  const result = await new Promise<{ code: number; stderr: string }>((resolve) => {
    const child = spawn(command, cmdArgs, { windowsHide: true });
    let stderr = "";
    child.stderr.on("data", (buf) => {
      stderr += String(buf);
    });
    child.on("close", (code) => resolve({ code: typeof code === "number" ? code : 1, stderr }));
    child.on("error", (err) => resolve({ code: 1, stderr: String(err.message ?? err) }));
  });
  if (result.code === 0) {
    return { attempted: true, success: true, detail: `Started via docker compose: ${composeFileAbs}` };
  }
  return {
    attempted: true,
    success: false,
    detail: `docker compose start failed (${composeFileAbs}): ${result.stderr.trim()}`,
  };
}

function selectRuntimeContext(args: {
  runtimeContexts: ProjectRuntimeContext[];
  requestedName?: string;
}): { selected?: ProjectRuntimeContext; reasonCode?: ProjectContextBlockedReason; nextAction?: string; requiredUserAction?: string[] } {
  const { runtimeContexts, requestedName } = args;
  if (runtimeContexts.length === 0) return {};
  if (requestedName) {
    const match = runtimeContexts.find((entry) => entry.name === requestedName);
    if (!match) {
      return {
        reasonCode: "runtime_context_unknown",
        nextAction: `Choose an existing runtime context instead of '${requestedName}'.`,
        requiredUserAction: [`Unknown runtime context '${requestedName}'.`],
      };
    }
    return { selected: match };
  }
  const terminalNamed = runtimeContexts.find(
    (entry) => entry.mode === "terminal" && entry.name === "terminal-cli",
  );
  if (terminalNamed) return { selected: terminalNamed };

  const terminal = runtimeContexts.find((entry) => entry.mode === "terminal");
  if (terminal) return { selected: terminal };

  if (runtimeContexts.length > 1) {
    return {
      reasonCode: "runtime_context_unknown",
      nextAction: "Provide runtimeContextName explicitly when multiple non-terminal runtime contexts exist.",
      requiredUserAction: ["Select runtimeContextName explicitly (for example docker-compose)."],
    };
  }

  const only = runtimeContexts[0];
  if (!only) return {};
  return { selected: only };
}

export async function resolveProjectContextForRegression(
  args: ResolveProjectContextArgs,
): Promise<ProjectContextResolutionResult> {
  let parsed;
  try {
    parsed = await readProjectArtifact(args.projectsFileAbs);
  } catch {
    return {
      status: "blocked",
      reasonCode: "project_artifact_missing",
      requiredUserAction: [`Create project artifact at ${args.projectsFileAbs}.`],
    };
  }
  if (!parsed.ok) {
    return {
      status: "blocked",
      reasonCode: parsed.reasonCode,
      requiredUserAction: parsed.errors,
    };
  }
  const workspace = selectWorkspace(parsed.artifact.workspaces, args.workspaceRootAbs);
  if (!workspace) {
    return {
      status: "blocked",
      reasonCode: "workspace_root_invalid",
      checks: [],
      nextAction: `Add workspace projectRoot '${args.workspaceRootAbs}' to projects.json.`,
      requiredUserAction: [`Add workspace projectRoot '${args.workspaceRootAbs}' to projects.json.`],
    };
  }

  const effectiveWorkspace: ProjectWorkspaceEntry = {
    ...workspace,
    defaults: {
      ...(workspace.defaults ?? {}),
      ...(args.defaultsOverride ?? {}),
    },
  };

  const runtimeContexts = effectiveWorkspace.runtimeContexts ?? [];
  let selectedRuntimeContextName: string | undefined;
  let selectedRuntimeContext: ProjectRuntimeContext | undefined;
  if (runtimeContexts.length > 0) {
    const runtimeSelection = selectRuntimeContext({
      runtimeContexts,
      ...(args.runtimeContextName ? { requestedName: args.runtimeContextName } : {}),
    });
    if (runtimeSelection.reasonCode) {
      const blocked: ProjectContextResolutionResult = {
        status: "blocked",
        reasonCode: runtimeSelection.reasonCode,
        checks: [],
        requiredUserAction: runtimeSelection.requiredUserAction ?? ["Unknown runtime context."],
      };
      if (runtimeSelection.nextAction) blocked.nextAction = runtimeSelection.nextAction;
      return {
        ...blocked,
      };
    }
    selectedRuntimeContext = runtimeSelection.selected;
    selectedRuntimeContextName = runtimeSelection.selected?.name;
  }

  const env = args.env ?? process.env;
  const contextPatch: Record<string, unknown> = {};
  const bearerKey = effectiveWorkspace.variables?.bearerTokenEnv;
  if (bearerKey) {
    const bearer = env[bearerKey];
    if (!bearer || bearer.trim().length === 0) {
      return {
        status: "blocked",
        reasonCode: "env_key_missing",
        missing: [bearerKey],
        checks: [],
        nextAction: `Set ${bearerKey} in .env or environment and retry.`,
        requiredUserAction: [`Set env key '${bearerKey}' before running regression.`],
      };
    }
    contextPatch["auth.bearer"] = bearer;
  }

  if (selectedRuntimeContext) {
    contextPatch["runtime.context.name"] = selectedRuntimeContext.name;
    contextPatch["runtime.context.mode"] = selectedRuntimeContext.mode;
    const autoStart =
      typeof selectedRuntimeContext.autoStart === "boolean"
        ? selectedRuntimeContext.autoStart
        : true;
    const autoStopOnFinish =
      typeof selectedRuntimeContext.autoStopOnFinish === "boolean"
        ? selectedRuntimeContext.autoStopOnFinish
        : true;
    contextPatch["runtime.autoStart"] = autoStart;
    contextPatch["runtime.autoStopOnFinish"] = autoStopOnFinish;
  }

  if (args.healthChecksEnabled !== false) {
    const prereqResult = await executeRunPrerequisites({
      workspace: effectiveWorkspace,
      workspaceRootAbs: args.workspaceRootAbs,
      env,
      contextPatch,
    });
    if (prereqResult.status === "blocked") {
      return {
        status: "blocked",
        reasonCode: prereqResult.reasonCode,
        checks: prereqResult.checks,
        nextAction: prereqResult.nextAction,
        requiredUserAction: prereqResult.requiredUserAction,
      };
    }
    const prereqChecks = prereqResult.checks;
    let health = await runRequiredHealthChecksWithDedupe({
      workspace: effectiveWorkspace,
      skipKeys: prereqResult.dedupeKeys,
    });
    let autoStartDetail: string | undefined;
    let autoStartAttempted = false;
    let autoStarted = false;
    const autoStartEnabled =
      selectedRuntimeContext ? (typeof selectedRuntimeContext.autoStart === "boolean" ? selectedRuntimeContext.autoStart : true) : false;
    if (!health.ok && selectedRuntimeContext && autoStartEnabled) {
      const starter = args.runtimeStarter ?? defaultRuntimeStarter;
      const startResult = await starter({
        runtimeContext: selectedRuntimeContext,
        workspaceRootAbs: workspace.projectRoot,
      });
      autoStartAttempted = startResult.attempted;
      autoStarted = startResult.success;
      autoStartDetail = startResult.detail;
      if (autoStarted) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
      health = await runRequiredHealthChecksWithDedupe({
        workspace: effectiveWorkspace,
        skipKeys: prereqResult.dedupeKeys,
      });
    }
    let strictProbeBases =
      Array.isArray(args.strictProbeBaseUrls)
        ? args.strictProbeBaseUrls
            .filter((entry): entry is string => typeof entry === "string")
            .map((entry) => entry.trim())
            .filter((entry) => entry.length > 0)
        : [];
    if (
      strictProbeBases.length === 0 &&
      args.strictProbeVerification === true &&
      selectedRuntimeContext?.mode === "terminal" &&
      Array.isArray(selectedRuntimeContext.startups) &&
      selectedRuntimeContext.startups.length > 0
    ) {
      const registry = await readProbeRegistryFromWorkspace(workspace.projectRoot);
      if (registry.ok) {
        const derived = selectedRuntimeContext.startups
          .map((startup) =>
            resolveProbeBaseUrlFromRegistry({
              registry: registry.registry,
              profileName: registry.profileName,
              probeId: startup.name,
            }),
          )
          .filter((entry): entry is string => typeof entry === "string" && entry.length > 0);
        strictProbeBases = [...new Set(derived)];
      }
    }
    if (health.ok && strictProbeBases.length > 0) {
      const timeoutMs =
        typeof effectiveWorkspace.defaults?.requestTimeoutMs === "number" && Number.isFinite(effectiveWorkspace.defaults.requestTimeoutMs) && effectiveWorkspace.defaults.requestTimeoutMs > 0
          ? Math.floor(effectiveWorkspace.defaults.requestTimeoutMs)
          : 3000;
      let unreachableBases: string[] = [];
      for (const probeBase of strictProbeBases) {
        const reachable = await httpCheck(`${probeBase.replace(/\/$/, "")}/__probe/status`, "GET", timeoutMs);
        if (!reachable) unreachableBases.push(probeBase);
      }
      if (unreachableBases.length > 0 && selectedRuntimeContext && autoStartEnabled) {
        const starter = args.runtimeStarter ?? defaultRuntimeStarter;
        const startResult = await starter({
          runtimeContext: selectedRuntimeContext,
          workspaceRootAbs: workspace.projectRoot,
        });
        autoStartAttempted = autoStartAttempted || startResult.attempted;
        autoStarted = startResult.success;
        autoStartDetail = startResult.detail;
        if (autoStarted) {
          await new Promise((resolve) => setTimeout(resolve, 1500));
        }
        unreachableBases = [];
        for (const probeBase of strictProbeBases) {
          const reachable = await httpCheck(`${probeBase.replace(/\/$/, "")}/__probe/status`, "GET", timeoutMs);
          if (!reachable) unreachableBases.push(probeBase);
        }
      }
      if (unreachableBases.length > 0) {
        const checks = [
          ...prereqChecks,
          ...strictProbeBases.map((probeBase) =>
          `probe:${probeBase}=${unreachableBases.includes(probeBase) ? "unreachable" : "ready"}`,
          ),
        ];
        if (autoStartAttempted) {
          checks.push(`runtime:auto_start=${autoStarted ? "ok" : "failed"}`);
        }
        if (autoStartDetail) {
          checks.push(`runtime:auto_start_detail=${autoStartDetail}`);
        }
        return {
          status: "blocked",
          reasonCode: "external_healthcheck_failed",
          checks,
          nextAction: "Start/restart runtime with MCP javaagent sidecar wiring and retry.",
          requiredUserAction: unreachableBases.map(
            (probeBase) => `Probe endpoint unreachable at ${probeBase}. Start/restart runtime with javaagent and retry.`,
          ),
        };
      }
    }
    if (!health.ok) {
      const checks = [...prereqChecks, ...health.checks];
      if (autoStartAttempted) {
        checks.push(`runtime:auto_start=${autoStarted ? "ok" : "failed"}`);
      }
      if (autoStartDetail) {
        checks.push(`runtime:auto_start_detail=${autoStartDetail}`);
      }
      return {
        status: "blocked",
        reasonCode: "external_healthcheck_failed",
        checks,
        nextAction: health.nextAction,
        requiredUserAction: health.requiredUserAction,
      };
    }
    if (autoStartAttempted) {
      contextPatch["runtime.autoStartAttempted"] = true;
      contextPatch["runtime.autoStarted"] = autoStarted;
      if (autoStartDetail) contextPatch["runtime.autoStartDetail"] = autoStartDetail;
    }
  }

  return {
    status: "ok",
    contextPatch,
    ...(selectedRuntimeContextName ? { runtimeContextName: selectedRuntimeContextName } : {}),
  };
}
