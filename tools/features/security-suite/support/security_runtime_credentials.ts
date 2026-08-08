import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

import { readProjectArtifact } from "@tools-feature-artifact-management";

import type {
  ProjectScriptEntry,
  ProjectWorkspaceEntry,
} from "@tools-project-artifact-spec/models/project_artifact.model";

const SECURITY_SCRIPT_TIMEOUT_MS = 20_000;

type SecurityRuntimeCredentialResolution =
  | {
      status: "ok";
      credentialContext: Record<string, string>;
      checks: string[];
    }
  | {
      status: "blocked";
      reasonCode:
        | "security_credential_context_binding_missing"
        | "security_credential_env_missing"
        | "security_credential_refresh_failed"
        | "security_project_context_unavailable";
      requiredUserAction: string[];
      checks: string[];
    };

function parseDotEnv(input: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const rawLine of input.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/u.test(key)) continue;
    let value = line.slice(separator + 1);
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

function resolveWorkspaceEnvFileAbs(args: {
  workspaceRootAbs: string;
  envFile: string | undefined;
}): string | undefined {
  if (!args.envFile) return undefined;
  return path.isAbsolute(args.envFile)
    ? args.envFile
    : path.resolve(args.workspaceRootAbs, args.envFile);
}

async function readWorkspaceEnvFile(args: {
  workspaceRootAbs: string;
  envFile: string | undefined;
}): Promise<Record<string, string>> {
  const envFileAbs = resolveWorkspaceEnvFileAbs(args);
  if (!envFileAbs) return {};
  try {
    return parseDotEnv(await fs.readFile(envFileAbs, "utf8"));
  } catch {
    return {};
  }
}

function resolveScriptArgs(args: {
  script: ProjectScriptEntry;
  workspaceRootAbs: string;
  envFileAbs: string | undefined;
}): string[] {
  const rendered = [...(args.script.args ?? [])];
  const fileArgIndex = rendered.findIndex((entry) => entry === "-File");
  if (fileArgIndex >= 0 && fileArgIndex + 1 < rendered.length) {
    const scriptPath = rendered[fileArgIndex + 1];
    if (typeof scriptPath === "string" && scriptPath.trim() && !path.isAbsolute(scriptPath)) {
      rendered[fileArgIndex + 1] = path.resolve(args.workspaceRootAbs, scriptPath);
    }
  } else {
    for (let index = 0; index < rendered.length; index += 1) {
      const value = rendered[index];
      if (
        typeof value === "string" &&
        (value.includes("/") || value.includes("\\")) &&
        !path.isAbsolute(value)
      ) {
        rendered[index] = path.resolve(args.workspaceRootAbs, value);
      }
    }
  }
  if (args.script.envFileArg && args.envFileAbs && !rendered.includes(args.script.envFileArg)) {
    rendered.push(args.script.envFileArg, args.envFileAbs);
  }
  return rendered;
}

async function runScript(args: {
  script: ProjectScriptEntry;
  workspaceRootAbs: string;
  envFileAbs: string | undefined;
  environment: Record<string, string | undefined>;
}): Promise<boolean> {
  let cwd = args.workspaceRootAbs;
  if (args.script.appdir) {
    cwd = path.isAbsolute(args.script.appdir)
      ? args.script.appdir
      : path.resolve(args.workspaceRootAbs, args.script.appdir);
  }
  const command = args.script.command === "node" ? process.execPath : args.script.command;
  const scriptArgs = resolveScriptArgs({
    script: args.script,
    workspaceRootAbs: args.workspaceRootAbs,
    envFileAbs: args.envFileAbs,
  });
  return await new Promise<boolean>((resolve) => {
    let settled = false;
    const finish = (ok: boolean): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(ok);
    };
    let child;
    try {
      child = spawn(command, scriptArgs, {
        cwd,
        env: { ...process.env, ...args.environment, ...(args.script.env ?? {}) },
        windowsHide: true,
      });
    } catch {
      resolve(false);
      return;
    }
    const timer = setTimeout(() => {
      child.kill();
      finish(false);
    }, SECURITY_SCRIPT_TIMEOUT_MS);
    child.once("error", () => finish(false));
    child.once("close", (code) => finish(code === 0));
  });
}

function resolvePrePlanScripts(args: {
  workspace: ProjectWorkspaceEntry;
  executionProfile: string;
}): ProjectScriptEntry[] {
  const profile = args.workspace.executionProfiles?.find(
    (entry) => entry.executionProfile === args.executionProfile && entry.suiteType === "security",
  );
  if (!profile) return [];
  const scriptsByName = new Map((args.workspace.scripts ?? []).map((entry) => [entry.name, entry]));
  return (profile.scriptRefs ?? []).flatMap((ref) => {
    const script = scriptsByName.get(ref.name);
    const phase = ref.phase ?? script?.phase ?? "prePlan";
    return script && phase === "prePlan" ? [script] : [];
  });
}

export async function resolveSecurityRuntimeCredentialContext(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
  credentialRefs: string[];
}): Promise<SecurityRuntimeCredentialResolution> {
  const credentialRefs = [...new Set(args.credentialRefs.map((entry) => entry.trim()).filter(Boolean))];
  if (credentialRefs.length === 0) {
    return { status: "ok", credentialContext: {}, checks: [] };
  }
  const projectsFileAbs = path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "projects.json",
  );
  const artifact = await readProjectArtifact(projectsFileAbs).catch(() => undefined);
  if (!artifact?.ok) {
    return {
      status: "blocked",
      reasonCode: "security_project_context_unavailable",
      checks: [],
      requiredUserAction: ["Restore a valid project Artifact before executing authenticated Security plans."],
    };
  }
  const workspace = artifact.artifact.workspaces.find(
    (entry) => path.resolve(entry.projectRoot) === path.resolve(args.workspaceRootAbs),
  );
  const profile = workspace?.executionProfiles?.find(
    (entry) => entry.executionProfile === args.executionProfile && entry.suiteType === "security",
  );
  if (!workspace || !profile) {
    return {
      status: "blocked",
      reasonCode: "security_project_context_unavailable",
      checks: [],
      requiredUserAction: [
        `Declare Security execution profile '${args.executionProfile}' for the selected project workspace.`,
      ],
    };
  }
  const contextBindings = workspace.variables?.contextBindings ?? {};
  const missingBindings = credentialRefs.filter(
    (credentialRef) => typeof contextBindings[credentialRef] !== "string",
  );
  if (missingBindings.length > 0) {
    return {
      status: "blocked",
      reasonCode: "security_credential_context_binding_missing",
      checks: [],
      requiredUserAction: missingBindings.map(
        (credentialRef) =>
          `Declare variables.contextBindings.${credentialRef} as the environment key for this credential.`,
      ),
    };
  }

  let environment: Record<string, string | undefined> = {
    ...process.env,
    ...(await readWorkspaceEnvFile({ workspaceRootAbs: args.workspaceRootAbs, envFile: workspace.envFile })),
  };
  const checks: string[] = [];
  const envFileAbs = resolveWorkspaceEnvFileAbs({
    workspaceRootAbs: args.workspaceRootAbs,
    envFile: workspace.envFile,
  });
  for (const script of resolvePrePlanScripts({ workspace, executionProfile: args.executionProfile })) {
    const ok = await runScript({
      script,
      workspaceRootAbs: args.workspaceRootAbs,
      envFileAbs,
      environment,
    });
    checks.push(`security_profile_script:prePlan:${script.name}=${ok ? "pass" : "fail"}`);
    if (!ok) {
      return {
        status: "blocked",
        reasonCode: "security_credential_refresh_failed",
        checks,
        requiredUserAction: [`Fix Security prePlan script '${script.name}' and retry.`],
      };
    }
    environment = {
      ...environment,
      ...(await readWorkspaceEnvFile({
        workspaceRootAbs: args.workspaceRootAbs,
        envFile: workspace.envFile,
      })),
    };
  }
  const credentialContext: Record<string, string> = {};
  const missingEnvKeys: string[] = [];
  for (const credentialRef of credentialRefs) {
    const envKey = contextBindings[credentialRef]!;
    const value = environment[envKey];
    if (!value?.trim()) {
      missingEnvKeys.push(envKey);
      continue;
    }
    credentialContext[credentialRef] = value;
  }
  if (missingEnvKeys.length > 0) {
    const uniqueMissing = [...new Set(missingEnvKeys)].sort((left, right) => left.localeCompare(right));
    return {
      status: "blocked",
      reasonCode: "security_credential_env_missing",
      checks,
      requiredUserAction: uniqueMissing.map(
        (envKey) => `Set environment key '${envKey}' in the configured project environment source.`,
      ),
    };
  }
  return { status: "ok", credentialContext, checks };
}
