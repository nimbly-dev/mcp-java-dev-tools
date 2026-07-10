import { escapeShSingleQuoted } from "../common";
import type {
  Healthcheck,
  HealthcheckCommand,
  RuntimeStartup,
} from "../models/execution_profile_export.model";

export function renderShRuntimeStartupSection(startups: RuntimeStartup[], includeRuntimeStartup: boolean): string[] {
  if (!includeRuntimeStartup || startups.length === 0) {
    return ["echo '[R00] runtime startup skipped by export options or no startup entries found'"];
  }

  const lines: string[] = [];
  const teardownCommands = startups
    .map((startup) => startup.teardownCommand)
    .filter((command): command is string => typeof command === "string" && command.trim().length > 0);
  if (teardownCommands.length > 0) {
    lines.push("__mcpjvm_runtime_teardown() {");
    for (const command of teardownCommands) {
      lines.push(`  ${command} >/dev/null 2>&1 || true`);
    }
    lines.push("}");
    lines.push("trap '__mcpjvm_runtime_teardown; rm -rf \"${__MCPJVM_EXPORT_TMP:-}\"' EXIT");
    lines.push("");
  }
  for (const startup of startups) {
    lines.push(`echo '[${startup.id}] ${escapeShSingleQuoted(startup.title)}'`);
    lines.push(startup.command);
    lines.push("if [ $? -ne 0 ]; then echo 'runtime startup failed' >&2; exit 1; fi");
    lines.push("");
  }
  return lines;
}

export function renderShHealthcheckCommands(checks: Healthcheck[]): HealthcheckCommand[] {
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
        command: `timeout 5 bash -c '</dev/tcp/${host}/${Number(port)}' >/dev/null 2>&1`,
      });
      continue;
    }

    if (check.type === "http" && check.url) {
      commands.push({
        id: check.id,
        title: check.title,
        command: `curl -fsS '${escapeShSingleQuoted(check.url)}' >/dev/null`,
      });
    }
  }
  return commands;
}

export function renderShHealthcheckSection(commands: HealthcheckCommand[], includeHealthcheckGate: boolean): string[] {
  if (!includeHealthcheckGate || commands.length === 0) {
    return ["echo '[H00] healthcheck gate skipped by export options or no healthchecks found'"];
  }

  const lines: string[] = [];
  for (const check of commands) {
    lines.push(`echo '[${check.id}] ${escapeShSingleQuoted(check.title)}'`);
    lines.push("attempt=0");
    lines.push("until [ $attempt -ge 30 ]");
    lines.push("do");
    lines.push(`  ${check.command}`);
    lines.push("  if [ $? -eq 0 ]; then break; fi");
    lines.push("  attempt=$((attempt+1))");
    lines.push("  sleep 2");
    lines.push("done");
    lines.push("if [ $attempt -ge 30 ]; then echo 'healthcheck gate failed' >&2; exit 1; fi");
    lines.push("");
  }
  return lines;
}
