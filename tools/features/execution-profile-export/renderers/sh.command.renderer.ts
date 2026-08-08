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
  const hasBackgroundStartup = startups.some((startup) => startup.background === true);
  const shouldStopOwnedProcesses = startups.some((startup) => startup.autoStopOnFinish === true);
  if (teardownCommands.length > 0 || hasBackgroundStartup) {
    if (hasBackgroundStartup) {
      lines.push('__MCPJVM_EXPORT_TMP="${TMPDIR:-/tmp}/mcpjvm-execution-profile-$$"');
      lines.push('mkdir -p "${__MCPJVM_EXPORT_TMP}"');
      lines.push('__MCPJVM_OWNED_RUNTIME_PIDS=()');
    }
    lines.push("__mcpjvm_runtime_teardown() {");
    if (hasBackgroundStartup && shouldStopOwnedProcesses) {
      lines.push('  for __mcpjvm_pid in "${__MCPJVM_OWNED_RUNTIME_PIDS[@]}"; do');
      lines.push('    if kill -0 "${__mcpjvm_pid}" >/dev/null 2>&1; then kill "${__mcpjvm_pid}" >/dev/null 2>&1 || true; fi');
      lines.push("  done");
    }
    for (const command of teardownCommands) {
      lines.push(`  ${command} >/dev/null 2>&1 || true`);
    }
    lines.push("}");
    lines.push("trap '__mcpjvm_exit_status=$?; if declare -F __mcpjvm_dynamic_attach_cleanup >/dev/null 2>&1; then __mcpjvm_dynamic_attach_cleanup || __mcpjvm_exit_status=$?; fi; __mcpjvm_runtime_teardown; rm -rf \"${__MCPJVM_EXPORT_TMP:-}\"; trap - EXIT; exit \"${__mcpjvm_exit_status}\"' EXIT");
    lines.push("");
  }
  for (const startup of startups) {
    lines.push(`echo '[${startup.id}] ${escapeShSingleQuoted(startup.title)}'`);
    if (startup.background) {
      lines.push(`${startup.command} >"\${__MCPJVM_EXPORT_TMP}/runtime-${startup.id}.log" 2>&1 &`);
      lines.push("__mcpjvm_runtime_pid=$!");
      lines.push('if ! kill -0 "${__mcpjvm_runtime_pid}" >/dev/null 2>&1; then echo \'runtime startup failed\' >&2; exit 1; fi');
      lines.push('__MCPJVM_OWNED_RUNTIME_PIDS+=("${__mcpjvm_runtime_pid}")');
    } else {
      lines.push(startup.command);
      lines.push("if [ $? -ne 0 ]; then echo 'runtime startup failed' >&2; exit 1; fi");
    }
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
