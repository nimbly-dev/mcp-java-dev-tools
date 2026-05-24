import { escapePsSingleQuoted } from "@tools-export-execution-profile/common";
import type { Healthcheck, HealthcheckCommand, RuntimeStartup } from "@tools-export-execution-profile/models/execution_profile_export.model";

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
        command: [
          "$attempt = 0",
          "$__health_ok = $false",
          "while ($attempt -lt 30) {",
          `  $__health_result = Test-NetConnection -ComputerName '${escapePsSingleQuoted(host)}' -Port ${Number(port)} -WarningAction SilentlyContinue`,
          "  if ($__health_result.TcpTestSucceeded -eq $true) { $__health_ok = $true; break }",
          "  $attempt += 1",
          "  Start-Sleep -Seconds 2",
          "}",
          "if (-not $__health_ok) { throw 'healthcheck gate failed' }",
        ].join("\n"),
      });
      continue;
    }

    if (check.type === "http" && check.url) {
      commands.push({
        id: check.id,
        title: check.title,
        command: [
          "$attempt = 0",
          "$__health_ok = $false",
          "while ($attempt -lt 30) {",
          "  try {",
          `    $__health_response = Invoke-WebRequest -UseBasicParsing -Uri '${escapePsSingleQuoted(check.url)}' -TimeoutSec 5`,
          "    if ($__health_response.StatusCode -ge 200 -and $__health_response.StatusCode -lt 500) { $__health_ok = $true; break }",
          "  } catch { }",
          "  $attempt += 1",
          "  Start-Sleep -Seconds 2",
          "}",
          "if (-not $__health_ok) { throw 'healthcheck gate failed' }",
        ].join("\n"),
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
    lines.push("");
  }
  return lines;
}
