import { asString, isRecord } from "../common";
import type { RuntimeStartup } from "../models/execution_profile_export.model";
import { shellDoubleQuote, toWorkspaceShellPath } from "../shell_path";

export function collectRuntimeStartups(input: {
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
  workspaceRootAbs?: string;
}): RuntimeStartup[] {
  if (!input.workspace) {
    return [];
  }
  const runtimeContexts = Array.isArray(input.workspace.runtimeContexts) ? input.workspace.runtimeContexts : [];
  const runtimeContext = runtimeContexts.find((entry) => {
    if (!isRecord(entry)) return false;
    const name = asString(entry.name);
    if (input.runtimeContextName && name === input.runtimeContextName) {
      return true;
    }
    return !input.runtimeContextName && entry.autoStart === true;
  }) ?? runtimeContexts.find((entry) => isRecord(entry));
  if (!isRecord(runtimeContext)) {
    return [];
  }

  const startups: RuntimeStartup[] = [];
  const mode = asString(runtimeContext.mode);
  const composeFile = asString(runtimeContext.composeFile);
  const autoStopOnFinish = runtimeContext.autoStopOnFinish === true;
  if (mode === "docker" && composeFile) {
    const composePathForShell = input.workspaceRootAbs
      ? toWorkspaceShellPath({ workspaceRootAbs: input.workspaceRootAbs, rawPath: composeFile })
      : composeFile.replaceAll("\\", "/");
    startups.push({
      id: `R${String(startups.length + 1).padStart(2, "0")}`,
      title: `${asString(runtimeContext.name) ?? "docker-context"} compose up`,
      command: `docker compose -f ${shellDoubleQuote(composePathForShell)} up -d`,
      ...(autoStopOnFinish
        ? { teardownCommand: `docker compose -f ${shellDoubleQuote(composePathForShell)} down` }
        : {}),
    });
  }

  if (!Array.isArray(runtimeContext.startups)) {
    return startups;
  }

  for (const [idx, startup] of runtimeContext.startups.entries()) {
    if (!isRecord(startup)) {
      continue;
    }
    const command = asString(startup.command);
    if (!command) {
      continue;
    }
    const args = Array.isArray(startup.args)
      ? startup.args.filter((arg): arg is string => typeof arg === "string" && arg.trim().length > 0)
      : [];
    const appdir = asString(startup.appdir);
    const envs = isRecord(startup.env) ? startup.env : undefined;
    const envPrefix = envs
      ? Object.entries(envs)
          .filter((entry): entry is [string, string] => typeof entry[1] === "string")
          .map(([key, value]) => `${key}="${value.replace(/"/g, '\\"')}"`)
          .join(" ")
      : "";
    const base = [command, ...args].join(" ");
    const withEnv = envPrefix.length > 0 ? `${envPrefix} ${base}` : base;
    const appdirForShell = appdir && input.workspaceRootAbs
      ? toWorkspaceShellPath({ workspaceRootAbs: input.workspaceRootAbs, rawPath: appdir })
      : appdir;
    const shellCommand = appdirForShell ? `(cd ${shellDoubleQuote(appdirForShell)} && ${withEnv})` : withEnv;
    const title = asString(startup.name) ?? `startup-${idx + 1}`;
    startups.push({
      id: `R${String(startups.length + 1).padStart(2, "0")}`,
      title,
      command: shellCommand,
    });
  }
  return startups;
}
