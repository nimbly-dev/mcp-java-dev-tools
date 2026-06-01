import type {
  CommandExportArg,
  CommandExportBundledScript,
  CommandExportScriptPhase,
} from "@tools-export-execution-profile/sections/shared/command_export_package.util";
import { prepareCommandExportPackage } from "@tools-export-execution-profile/sections/shared/command_export_package.util";

export type Ps1ExportPackageSections = {
  preRuntimeScriptSection: string[];
  postRuntimeScriptSection: string[];
  postHealthcheckScriptSection: string[];
  prePlanScriptSection: string[];
};

function psSingleQuoted(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

function renderArg(arg: CommandExportArg): string {
  if (arg.kind === "exportPath") {
    return `(Join-Path $script:McpJvmExportScriptDir ${psSingleQuoted(arg.relPath.replaceAll("/", "\\"))})`;
  }
  if (arg.kind === "projectEnv") {
    return "($script:McpJvmProjectEnv)";
  }
  return psSingleQuoted(arg.value);
}

function renderScriptPhaseSection(input: {
  phase: CommandExportScriptPhase;
  title: string;
  scripts: CommandExportBundledScript[];
  functionName?: string;
}): string[] {
  const selected = input.scripts.filter((entry) => entry.phase === input.phase);
  const bodyLines: string[] = [];
  if (selected.length === 0) {
    bodyLines.push(`Write-Host '[S00] ${input.title} skipped; no shared scriptRefs for phase'`);
  }
  selected.forEach((entry, index) => {
    const id = `S${String(index + 1).padStart(2, "0")}`;
    const appdir = entry.script.appdir
      ? `Join-Path $script:McpJvmWorkspaceRoot ${psSingleQuoted(entry.script.appdir)}`
      : "$script:McpJvmWorkspaceRoot";
    const envLines = entry.script.env
      ? Object.entries(entry.script.env)
          .sort((left, right) => left[0].localeCompare(right[0]))
          .map(([key, value]) => `[Environment]::SetEnvironmentVariable('${key}', ${psSingleQuoted(value)}, 'Process')`)
      : [];
    bodyLines.push(`Write-Host '[${id}] ${input.phase} ${entry.script.name}'`);
    bodyLines.push(...envLines);
    bodyLines.push(`Push-Location (${appdir})`);
    bodyLines.push("try {");
    bodyLines.push(`  & ${psSingleQuoted(entry.script.command)} ${entry.args.map(renderArg).join(" ")}`);
    bodyLines.push("  if ($LASTEXITCODE -ne 0) { throw 'profile script failed' }");
    bodyLines.push("} finally {");
    bodyLines.push("  Pop-Location");
    bodyLines.push("}");
    bodyLines.push("Reload-WorkspaceEnv");
    bodyLines.push("");
  });
  if (input.functionName) {
    const lines: string[] = [];
    lines.push(`function ${input.functionName} {`);
    lines.push(...bodyLines.map((line) => `  ${line}`));
    lines.push("}");
    lines.push(`${input.functionName}`);
    return lines;
  }
  return bodyLines;
}

export async function preparePs1ExportPackage(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string | undefined;
  includeResolvedSecrets: boolean;
}): Promise<Ps1ExportPackageSections> {
  const commandPackage = await prepareCommandExportPackage({ ...input, mode: "ps1" });
  return {
    preRuntimeScriptSection: renderScriptPhaseSection({
      phase: "preRuntime",
      title: "pre-runtime scripts",
      scripts: commandPackage.bundledScripts,
    }),
    postRuntimeScriptSection: renderScriptPhaseSection({
      phase: "postRuntime",
      title: "post-runtime scripts",
      scripts: commandPackage.bundledScripts,
    }),
    postHealthcheckScriptSection: renderScriptPhaseSection({
      phase: "postHealthcheck",
      title: "post-healthcheck scripts",
      scripts: commandPackage.bundledScripts,
      functionName: "Invoke-PostHealthcheckScripts",
    }),
    prePlanScriptSection: renderScriptPhaseSection({
      phase: "prePlan",
      title: "pre-plan scripts",
      scripts: commandPackage.bundledScripts,
    }),
  };
}
