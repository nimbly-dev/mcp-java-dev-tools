/**
 * Regression runtime support owner.
 *
 * Environment loading, Probe configuration, script execution, health checks,
 * Windows process control, auto-start convergence, and prerequisite execution
 * are kept here as one explicit runtime-support boundary.
 */
import { spawn } from "node:child_process";
import { promises as fs } from "node:fs";
import path from "node:path";

import {
  tcpCheck,
  httpCheck,
  readWorkspaceEnvFile,
  resolveWorkspaceEnvFileAbs,
  resolveWorkspaceRequestTimeoutMs,
  resolveWorkspaceRetryMax,
} from "./project_probe_process_support";

import type {
  ProjectRuntimeContext,
  ProjectScriptEntry,
  ProjectScriptPhase,
  ProjectWorkspaceEntry,
  RunPrerequisite,
} from "@tools-project-artifact-spec/models/project_artifact.model";
import type {
  ProjectContextBlockedReason,
  ResolvedProfileScript,
} from "../models/regression_context.model";

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function selectWorkspace(
  workspaces: ProjectWorkspaceEntry[],
  workspaceRootAbs: string,
): ProjectWorkspaceEntry | null {
  const requestedRoot = path.resolve(workspaceRootAbs);
  for (const workspace of workspaces) {
    if (path.resolve(workspace.projectRoot) === requestedRoot) return workspace;
  }
  return null;
}

export function resolveProfileScripts(args: {
  workspace: ProjectWorkspaceEntry;
  executionProfileName?: string;
}): ResolvedProfileScript[] {
  if (!args.executionProfileName) return [];
  const profile = args.workspace.executionProfiles?.find(
    (entry) => entry.executionProfile === args.executionProfileName,
  );
  if (!profile || !Array.isArray(profile.scriptRefs) || profile.scriptRefs.length === 0) {
    return [];
  }
  const scripts = new Map((args.workspace.scripts ?? []).map((entry) => [entry.name, entry]));
  const resolved: ResolvedProfileScript[] = [];
  for (const scriptRef of profile.scriptRefs) {
    const script = scripts.get(scriptRef.name);
    if (!script) continue;
    resolved.push({
      script,
      phase: scriptRef.phase ?? script.phase ?? "prePlan",
    });
  }
  return resolved;
}

export function resolveScriptCommandArgs(args: {
  script: ProjectScriptEntry;
  workspaceRootAbs: string;
  envFileAbs: string | null;
}): string[] {
  const renderedArgs = [...(args.script.args ?? [])];
  const fileArgIndex = renderedArgs.findIndex((entry) => entry === "-File");
  if (fileArgIndex >= 0 && fileArgIndex + 1 < renderedArgs.length) {
    const scriptPath = renderedArgs[fileArgIndex + 1];
    if (
      typeof scriptPath === "string" &&
      scriptPath.trim().length > 0 &&
      !path.isAbsolute(scriptPath)
    ) {
      renderedArgs[fileArgIndex + 1] = path.resolve(args.workspaceRootAbs, scriptPath);
    }
  } else {
    for (let i = 0; i < renderedArgs.length; i += 1) {
      const value = renderedArgs[i];
      if (
        typeof value === "string" &&
        (value.includes("/") || value.includes("\\")) &&
        !path.isAbsolute(value)
      ) {
        renderedArgs[i] = path.resolve(args.workspaceRootAbs, value);
      }
    }
  }
  if (args.script.envFileArg && args.envFileAbs && !renderedArgs.includes(args.script.envFileArg)) {
    renderedArgs.push(args.script.envFileArg, args.envFileAbs);
  }
  return renderedArgs;
}

export function resolveScriptCommand(command: string): string {
  if (command === "node") {
    return process.execPath;
  }
  return command;
}

export async function executeSharedScriptsForPhase(args: {
  scripts: ResolvedProfileScript[];
  phase: ProjectScriptPhase;
  workspace: ProjectWorkspaceEntry;
  workspaceRootAbs: string;
  env: Record<string, string | undefined>;
}): Promise<
  | { status: "ok"; checks: string[]; env: Record<string, string | undefined> }
  | {
      status: "blocked";
      reasonCode: ProjectContextBlockedReason;
      checks: string[];
      nextAction: string;
      requiredUserAction: string[];
    }
> {
  const selected = args.scripts.filter((entry) => entry.phase === args.phase);
  if (selected.length === 0) {
    return { status: "ok", checks: [], env: args.env };
  }
  const checks: string[] = [];
  const envFileAbs = resolveWorkspaceEnvFileAbs({
    workspace: args.workspace,
    workspaceRootAbs: args.workspaceRootAbs,
  });
  const timeoutMs = resolveWorkspaceRequestTimeoutMs(args.workspace, 20_000);
  let currentEnv = { ...args.env };
  for (const entry of selected) {
    const script = entry.script;
    const cwd = script.appdir
      ? path.isAbsolute(script.appdir)
        ? script.appdir
        : path.resolve(args.workspaceRootAbs, script.appdir)
      : args.workspaceRootAbs;
    const scriptArgs = resolveScriptCommandArgs({
      script,
      workspaceRootAbs: args.workspaceRootAbs,
      envFileAbs,
    });
    const result = await new Promise<{ ok: boolean; detail: string }>((resolve) => {
      const child = spawn(resolveScriptCommand(script.command), scriptArgs, {
        cwd,
        env: { ...process.env, ...currentEnv, ...(script.env ?? {}) },
        windowsHide: true,
      });
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
          detail: code === 0 ? "ok" : stderr.trim() || `exit_code=${String(code ?? 1)}`,
        });
      });
      child.on("error", (err) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve({ ok: false, detail: String(err.message ?? err) });
      });
    });
    checks.push(
      `profile_script:${args.phase}:${script.name}=${result.ok ? "pass" : `fail(${result.detail})`}`,
    );
    if (!result.ok) {
      return {
        status: "blocked",
        reasonCode: "script_execution_failed",
        checks,
        nextAction: `Fix profile script '${script.name}' and retry.`,
        requiredUserAction: [`Profile script '${script.name}' failed: ${result.detail}`],
      };
    }
    currentEnv = {
      ...currentEnv,
      ...(await readWorkspaceEnvFile({
        workspace: args.workspace,
        workspaceRootAbs: args.workspaceRootAbs,
      })),
    };
  }
  return { status: "ok", checks, env: currentEnv };
}

export async function runRequiredHealthChecks(workspace: ProjectWorkspaceEntry): Promise<
  | {
      ok: true;
      checks: string[];
    }
  | {
      ok: false;
      checks: string[];
      failures: string[];
      nextAction: string;
      requiredUserAction: string[];
    }
> {
  const retryMax = resolveWorkspaceRetryMax(workspace, 1);
  const timeoutDefaultMs = resolveWorkspaceRequestTimeoutMs(workspace, 3000);
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
          ok = await httpCheck(check.url, check.method ?? "GET", timeoutMs, check.expect?.status);
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

export function buildHealthcheckKeyFromRunPrerequisite(prereq: RunPrerequisite): string | null {
  if (prereq.type !== "assert" || !prereq.assert) return null;
  if (prereq.assert.kind === "port_reachable" && prereq.assert.host && prereq.assert.port) {
    return `tcp:${prereq.assert.host}:${prereq.assert.port}`;
  }
  if (prereq.assert.kind === "url_reachable" && prereq.assert.url) {
    return `http:${prereq.assert.url}`;
  }
  return null;
}

export function buildHealthcheckKey(check: {
  type: "tcp" | "http";
  target?: string;
  url?: string;
}): string | null {
  if (check.type === "tcp" && typeof check.target === "string") return `tcp:${check.target}`;
  if (check.type === "http" && typeof check.url === "string") return `http:${check.url}`;
  return null;
}

export async function runRequiredHealthChecksWithDedupe(args: {
  workspace: ProjectWorkspaceEntry;
  skipKeys: Set<string>;
}): Promise<
  | {
      ok: true;
      checks: string[];
    }
  | {
      ok: false;
      checks: string[];
      failures: string[];
      nextAction: string;
      requiredUserAction: string[];
    }
> {
  const retryMax = resolveWorkspaceRetryMax(args.workspace, 1);
  const timeoutDefaultMs = resolveWorkspaceRequestTimeoutMs(args.workspace, 3000);
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
          ok = await httpCheck(check.url, check.method ?? "GET", timeoutMs, check.expect?.status);
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

export function resolveAutoStartHealthConvergenceAttempts(args: {
  workspace: ProjectWorkspaceEntry;
  runtimeMode: ProjectRuntimeContext["mode"];
}): number {
  const retryMax = resolveWorkspaceRetryMax(args.workspace, 1);
  const baseline = args.runtimeMode === "docker" ? 10 : 3;
  return Math.max(retryMax, baseline);
}

export function resolveAutoStartHealthConvergenceDelayMs(args: {
  workspace: ProjectWorkspaceEntry;
  runtimeMode: ProjectRuntimeContext["mode"];
}): number {
  const timeoutMs = resolveWorkspaceRequestTimeoutMs(args.workspace, 3000);
  const baseline =
    args.runtimeMode === "docker" ? Math.floor(timeoutMs / 3) : Math.floor(timeoutMs / 4);
  const maxDelayMs = args.runtimeMode === "docker" ? 1000 : 500;
  return Math.min(maxDelayMs, Math.max(100, baseline));
}

export async function waitForRequiredHealthChecksAfterAutoStart(args: {
  workspace: ProjectWorkspaceEntry;
  skipKeys: Set<string>;
  runtimeMode: ProjectRuntimeContext["mode"];
}): Promise<Awaited<ReturnType<typeof runRequiredHealthChecksWithDedupe>>> {
  const maxAttempts = resolveAutoStartHealthConvergenceAttempts({
    workspace: args.workspace,
    runtimeMode: args.runtimeMode,
  });
  const delayMs = resolveAutoStartHealthConvergenceDelayMs({
    workspace: args.workspace,
    runtimeMode: args.runtimeMode,
  });
  let last = await runRequiredHealthChecksWithDedupe({
    workspace: args.workspace,
    skipKeys: args.skipKeys,
  });
  for (let attempt = 1; attempt < maxAttempts && !last.ok; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    last = await runRequiredHealthChecksWithDedupe({
      workspace: args.workspace,
      skipKeys: args.skipKeys,
    });
  }
  return last;
}

export async function isCommandAvailable(commandName: string): Promise<boolean> {
  const bin = process.platform === "win32" ? "where" : "which";
  return await new Promise<boolean>((resolve) => {
    const child = spawn(bin, [commandName], { windowsHide: true });
    child.on("close", (code) => resolve(code === 0));
    child.on("error", () => resolve(false));
  });
}

export async function executeRunPrerequisites(args: {
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
  const timeoutDefaultMs = resolveWorkspaceRequestTimeoutMs(args.workspace, 3000);
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
        ok = await tcpCheck(
          `${prereq.assert.host}:${prereq.assert.port}`,
          prereq.assert.timeoutMs ?? timeoutDefaultMs,
        );
      } else if (prereq.assert.kind === "url_reachable") {
        ok = await httpCheck(
          prereq.assert.url ?? "",
          "GET",
          prereq.assert.timeoutMs ?? timeoutDefaultMs,
        );
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
        ? path.isAbsolute(script.cwd)
          ? script.cwd
          : path.resolve(args.workspaceRootAbs, script.cwd)
        : args.workspaceRootAbs;
      const timeoutMs =
        typeof script.timeoutMs === "number" && script.timeoutMs > 0
          ? script.timeoutMs
          : timeoutDefaultMs;
      let command = "";
      let cmdArgs: string[] = [];
      if (script.command === "node") {
        command = process.execPath;
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else if (script.command === "python") {
        command = "python";
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else if (script.command === "sh") {
        command = "sh";
        cmdArgs = [scriptAbs, ...(script.args ?? [])];
      } else {
        command = "powershell";
        cmdArgs = [
          "-NoProfile",
          "-ExecutionPolicy",
          "Bypass",
          "-File",
          scriptAbs,
          ...(script.args ?? []),
        ];
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
            detail: code === 0 ? "ok" : stderr.trim() || `exit_code=${String(code ?? 1)}`,
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
