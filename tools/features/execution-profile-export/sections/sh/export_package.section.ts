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
  functionName?: string;
}): string[] {
  const selected = input.scripts.filter((entry) => entry.phase === input.phase);
  const bodyLines: string[] = [];
  if (selected.length === 0) {
    bodyLines.push(`echo '[S00] ${input.title} skipped; no shared scriptRefs for phase'`);
  }
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
    bodyLines.push(`echo '[${id}] ${input.phase} ${entry.script.name}'`);
    bodyLines.push("set +e");
    bodyLines.push(`(cd "${appdir}" && ${commandLine})`);
    bodyLines.push("__mcpjvm_script_rc=$?");
    bodyLines.push("set -e");
    bodyLines.push("if [ $__mcpjvm_script_rc -ne 0 ]; then echo 'profile script failed' >&2; exit 1; fi");
    bodyLines.push("reload_workspace_env");
    bodyLines.push("");
  });
  if (input.functionName) {
    const lines: string[] = [];
    lines.push(`${input.functionName}() {`);
    lines.push(...bodyLines.map((line) => `  ${line}`));
    lines.push("}");
    lines.push(`${input.functionName}`);
    return lines;
  }
  return bodyLines;
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
      functionName: "invoke_posthealthcheck_scripts",
    }),
    prePlanScriptSection: renderScriptPhaseSection({
      phase: "prePlan",
      title: "pre-plan scripts",
      scripts: commandPackage.bundledScripts,
      workspaceRootAbs: input.workspaceRootAbs,
    }),
  };
}
