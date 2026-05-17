import { escapePsSingleQuoted } from "@tools-export-run-session/common";
import type { Healthcheck, HealthcheckCommand, RuntimeStartup } from "@tools-export-run-session/models/run_session_export.model";

export function renderPs1RuntimeStartupSection(startups: RuntimeStartup[], includeRuntimeStartup: boolean): string[] {
  if (!includeRuntimeStartup || startups.length === 0) {
    return ["Write-Host '[R00] runtime startup skipped by export options or no startup entries found'"];
  }

  const lines: string[] = [];
  for (const startup of startups) {
    lines.push(`Write-Host '[${startup.id}] ${escapePsSingleQuoted(startup.title)}'`);
    lines.push(startup.command);
    lines.push("if ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }");
    lines.push("");
  }
  return lines;
}

export function renderPs1HealthcheckCommands(checks: Healthcheck[]): HealthcheckCommand[] {
  const commands: HealthcheckCommand[] = [];
  for (const check of checks) {
    if (check.type === "tcp" && check.target) {
      const [host, port] = check.target.split(":");
      if (!host || !port) {
        continue;
      }
      commands.push({
        id: check.id,
        title: check.title,
        command: `Test-NetConnection -ComputerName '${escapePsSingleQuoted(host)}' -Port ${Number(port)} -WarningAction SilentlyContinue | Out-Null`,
      });
      continue;
    }

    if (check.type === "http" && check.url) {
      commands.push({
        id: check.id,
        title: check.title,
        command: `Invoke-WebRequest -UseBasicParsing -Uri '${escapePsSingleQuoted(check.url)}' | Out-Null`,
      });
    }
  }
  return commands;
}

export function renderPs1HealthcheckSection(
  commands: HealthcheckCommand[],
  includeHealthcheckGate: boolean,
): string[] {
  if (!includeHealthcheckGate || commands.length === 0) {
    return ["Write-Host '[H00] healthcheck gate skipped by export options or no healthchecks found'"];
  }

  const lines: string[] = [];
  for (const check of commands) {
    lines.push(`Write-Host '[${check.id}] ${escapePsSingleQuoted(check.title)}'`);
    lines.push(check.command);
    lines.push("if ($LASTEXITCODE -ne 0) { throw 'healthcheck gate failed' }");
    lines.push("");
  }
  return lines;
}
