import { asString, isRecord } from "@tools-export-execution-profile/common";

function psSingleQuoted(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

function resolveRuntimeContext(input: {
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
}): Record<string, unknown> | undefined {
  const runtimeContexts = Array.isArray(input.workspace?.runtimeContexts) ? input.workspace.runtimeContexts : [];
  const selected =
    runtimeContexts.find((entry) => {
      if (!isRecord(entry)) return false;
      const name = asString(entry.name);
      if (input.runtimeContextName && name === input.runtimeContextName) return true;
      return !input.runtimeContextName && entry.autoStart === true;
    }) ?? runtimeContexts.find((entry) => isRecord(entry));
  return isRecord(selected) ? selected : undefined;
}

export function buildPs1RuntimeStartupSection(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
  includeRuntimeStartup: boolean;
}): string[] {
  const runtimeContext = resolveRuntimeContext(input);
  if (!input.includeRuntimeStartup || !runtimeContext) {
    return ["Write-Host '[R00] runtime startup skipped by export options or no startup entries found'"];
  }

  const lines: string[] = [];
  const mode = asString(runtimeContext.mode);
  const composeFile = asString(runtimeContext.composeFile);
  if (mode === "docker" && composeFile) {
    const title = `${asString(runtimeContext.name) ?? "docker-context"} compose up`;
    lines.push(`Write-Host '[R${String(lines.length + 1).padStart(2, "0")}] ${title}'`);
    lines.push(`docker compose -f (Join-Path $script:McpJvmWorkspaceRoot ${psSingleQuoted(composeFile)}) up -d`);
    lines.push("if ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }");
    lines.push("");
  }

  const startups = Array.isArray(runtimeContext.startups) ? runtimeContext.startups : [];
  let startupIndex = mode === "docker" && composeFile ? 1 : 0;
  for (const startup of startups) {
    if (!isRecord(startup)) continue;
    const command = asString(startup.command);
    if (!command) continue;
    startupIndex += 1;
    const title = asString(startup.name) ?? `startup-${startupIndex}`;
    const args = Array.isArray(startup.args)
      ? startup.args.filter((arg): arg is string => typeof arg === "string" && arg.trim().length > 0)
      : [];
    const appdir = asString(startup.appdir);
    const envs = isRecord(startup.env) ? startup.env : undefined;
    lines.push(`Write-Host '[R${String(startupIndex).padStart(2, "0")}] ${title}'`);
    if (envs) {
      for (const [key, value] of Object.entries(envs).sort((left, right) => left[0].localeCompare(right[0]))) {
        if (typeof value !== "string") continue;
        lines.push(`[Environment]::SetEnvironmentVariable('${key}', ${psSingleQuoted(value)}, 'Process')`);
      }
    }
    if (appdir) {
      lines.push(`Push-Location (Join-Path $script:McpJvmWorkspaceRoot ${psSingleQuoted(appdir)})`);
      lines.push("try {");
      lines.push(`  & ${psSingleQuoted(command)} ${args.map(psSingleQuoted).join(" ")}`);
      lines.push("  if ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }");
      lines.push("} finally {");
      lines.push("  Pop-Location");
      lines.push("}");
    } else {
      lines.push(`& ${psSingleQuoted(command)} ${args.map(psSingleQuoted).join(" ")}`);
      lines.push("if ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }");
    }
    lines.push("");
  }

  if (lines.length === 0) {
    return ["Write-Host '[R00] runtime startup skipped by export options or no startup entries found'"];
  }
  return lines;
}
