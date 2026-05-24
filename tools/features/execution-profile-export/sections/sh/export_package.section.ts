import { shellDoubleQuote, toWorkspaceShellPath } from "@tools-export-execution-profile/shell_path.util";
import type {
  CommandExportArg,
  CommandExportBundledScript,
  CommandExportScriptPhase,
} from "@tools-export-execution-profile/sections/shared/command_export_package.util";
import { prepareCommandExportPackage } from "@tools-export-execution-profile/sections/shared/command_export_package.util";

export type ShExportPackageSections = {
  preRuntimeScriptSection: string[];
  postRuntimeScriptSection: string[];
  postHealthcheckScriptSection: string[];
  prePlanScriptSection: string[];
};

function renderArg(arg: CommandExportArg): string {
  if (arg.kind === "exportPath") {
    return shellDoubleQuote(`\${__MCPJVM_EXPORT_SCRIPT_DIR}/${arg.relPath}`);
  }
  if (arg.kind === "projectEnv") {
    return shellDoubleQuote("${__MCPJVM_PROJECT_ENV}");
  }
  return shellDoubleQuote(arg.value);
}

function renderEnvPrefix(env: Record<string, string> | undefined): string {
  if (!env || Object.keys(env).length === 0) return "";
  return `${Object.entries(env)
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([key, value]) => `${key}=${shellDoubleQuote(value)}`)
    .join(" ")} `;
}

function renderScriptPhaseSection(input: {
  phase: CommandExportScriptPhase;
  title: string;
  scripts: CommandExportBundledScript[];
  workspaceRootAbs: string;
}): string[] {
  const selected = input.scripts.filter((entry) => entry.phase === input.phase);
  if (selected.length === 0) return [`echo '[S00] ${input.title} skipped; no shared scriptRefs for phase'`];

  const lines: string[] = [];
  selected.forEach((entry, index) => {
    const id = `S${String(index + 1).padStart(2, "0")}`;
    const appdir = entry.script.appdir
      ? toWorkspaceShellPath({ workspaceRootAbs: input.workspaceRootAbs, rawPath: entry.script.appdir })
      : "${__MCPJVM_WORKSPACE_ROOT}";
    const commandLine = [
      renderEnvPrefix(entry.script.env),
      shellDoubleQuote(entry.script.command),
      ...entry.args.map(renderArg),
    ]
      .join(" ")
      .trim();
    lines.push(`echo '[${id}] ${input.phase} ${entry.script.name}'`);
    lines.push("set +e");
    lines.push(`(cd "${appdir}" && ${commandLine})`);
    lines.push("__mcpjvm_script_rc=$?");
    lines.push("set -e");
    lines.push("if [ $__mcpjvm_script_rc -ne 0 ]; then echo 'profile script failed' >&2; exit 1; fi");
    lines.push("reload_workspace_env");
    lines.push("");
  });
  return lines;
}

export async function prepareShExportPackage(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string | undefined;
  includeResolvedSecrets: boolean;
}): Promise<ShExportPackageSections> {
  const commandPackage = await prepareCommandExportPackage({ ...input, mode: "sh" });
  return {
    preRuntimeScriptSection: renderScriptPhaseSection({
      phase: "preRuntime",
      title: "pre-runtime scripts",
      scripts: commandPackage.bundledScripts,
      workspaceRootAbs: input.workspaceRootAbs,
    }),
    postRuntimeScriptSection: renderScriptPhaseSection({
      phase: "postRuntime",
      title: "post-runtime scripts",
      scripts: commandPackage.bundledScripts,
      workspaceRootAbs: input.workspaceRootAbs,
    }),
    postHealthcheckScriptSection: renderScriptPhaseSection({
      phase: "postHealthcheck",
      title: "post-healthcheck scripts",
      scripts: commandPackage.bundledScripts,
      workspaceRootAbs: input.workspaceRootAbs,
    }),
    prePlanScriptSection: renderScriptPhaseSection({
      phase: "prePlan",
      title: "pre-plan scripts",
      scripts: commandPackage.bundledScripts,
      workspaceRootAbs: input.workspaceRootAbs,
    }),
  };
}
