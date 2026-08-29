package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;

/** Renders runtime-startup and healthcheck gates for shell replay scripts. */
final class ExecutionExportRuntimeRenderer {

    void appendPowerShellRuntime(
            StringBuilder script, ExecutionExportOptions options, JsonNode profile) {
        if (!options.includeRuntimeStartup()) {
            script.append("Write-Host '[R00] runtime startup skipped by export options'\n");
            return;
        }
        JsonNode context = runtimeContext(options.workspace(), profile);
        if (context == null) {
            script.append("Write-Host '[R00] runtime startup skipped: no runtime context'\n");
            return;
        }
        if ("docker".equals(context.path("mode").asText())
                && context.path("composeFile").isTextual()) {
            script.append("docker compose -f ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                            context.path("composeFile").asText()))
                    .append(" up -d\nif ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }\n");
        }
        for (JsonNode startup : context.path("startups")) {
            String command = startup.path("command").asText("");
            if (command.isBlank()) {
                continue;
            }
            script.append("Write-Host ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                            "[R] " + startup.path("name").asText("startup")))
                    .append("\n")
                    .append("& ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(command));
            for (JsonNode arg : startup.path("args")) {
                if (arg.isTextual()) {
                    script.append(" ")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted(arg.asText()));
                }
            }
            script.append("\nif ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }\n");
        }
    }

    void appendShellRuntime(
            StringBuilder script, ExecutionExportOptions options, JsonNode profile) {
        if (!options.includeRuntimeStartup()) {
            script.append("echo '[R00] runtime startup skipped by export options'\n");
            return;
        }
        JsonNode context = runtimeContext(options.workspace(), profile);
        if (context == null) {
            script.append("echo '[R00] runtime startup skipped: no runtime context'\n");
            return;
        }
        if ("docker".equals(context.path("mode").asText())
                && context.path("composeFile").isTextual()) {
            script.append("docker compose -f ")
                    .append(ExecutionExportShellQuoting.shellSingleQuoted(
                            context.path("composeFile").asText()))
                    .append(" up -d\n");
        }
        for (JsonNode startup : context.path("startups")) {
            String command = startup.path("command").asText("");
            if (command.isBlank()) {
                continue;
            }
            script.append("echo ")
                    .append(ExecutionExportShellQuoting.shellSingleQuoted(
                            "[R] " + startup.path("name").asText("startup")))
                    .append("\n")
                    .append(ExecutionExportShellQuoting.shellSingleQuoted(command));
            for (JsonNode arg : startup.path("args")) {
                if (arg.isTextual()) {
                    script.append(" ")
                            .append(ExecutionExportShellQuoting.shellSingleQuoted(arg.asText()));
                }
            }
            script.append(" &\n");
        }
    }

    void appendPowerShellHealthchecks(
            StringBuilder script, JsonNode workspace, boolean enabled) {
        if (!enabled) {
            script.append("Write-Host '[H00] healthcheck gate skipped by export options'\n");
            return;
        }
        for (JsonNode system : workspace.path("externalSystems")) {
            for (JsonNode check : system.path("healthChecks")) {
                String id = check.path("id").asText("healthcheck");
                if ("http".equals(check.path("type").asText())
                        && check.path("url").isTextual()) {
                    script.append("Write-Host ")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted("[H] " + id))
                            .append("\n")
                            .append("$__health = Invoke-WebRequest -UseBasicParsing -Uri ")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                                    check.path("url").asText()))
                            .append(" -TimeoutSec 5\n")
                            .append("if ($__health.StatusCode -lt 200 -or $__health.StatusCode -ge 500) { ")
                            .append("throw 'healthcheck gate failed' }\n");
                }
                if ("tcp".equals(check.path("type").asText())
                        && check.path("target").isTextual()) {
                    String[] target = check.path("target").asText().split(":", 2);
                    if (target.length == 2) {
                        script.append("if (-not (Test-NetConnection -ComputerName ")
                                .append(ExecutionExportShellQuoting.powerShellSingleQuoted(target[0]))
                                .append(" -Port ").append(target[1])
                                .append(" -WarningAction SilentlyContinue).TcpTestSucceeded) { ")
                                .append("throw 'healthcheck gate failed' }\n");
                    }
                }
            }
        }
    }

    void appendShellHealthchecks(
            StringBuilder script, JsonNode workspace, boolean enabled) {
        if (!enabled) {
            script.append("echo '[H00] healthcheck gate skipped by export options'\n");
            return;
        }
        for (JsonNode system : workspace.path("externalSystems")) {
            for (JsonNode check : system.path("healthChecks")) {
                String id = check.path("id").asText("healthcheck");
                if ("http".equals(check.path("type").asText())
                        && check.path("url").isTextual()) {
                    script.append("echo ")
                            .append(ExecutionExportShellQuoting.shellSingleQuoted("[H] " + id))
                            .append("\n")
                            .append("curl --fail --silent --show-error ")
                            .append(ExecutionExportShellQuoting.shellSingleQuoted(
                                    check.path("url").asText()))
                            .append(" >/dev/null\n");
                }
                if ("tcp".equals(check.path("type").asText())
                        && check.path("target").isTextual()) {
                    String[] target = check.path("target").asText().split(":", 2);
                    if (target.length == 2) {
                        script.append("timeout 5 bash -c ")
                                .append(ExecutionExportShellQuoting.shellSingleQuoted(
                                        "</dev/tcp/" + target[0] + "/" + target[1]))
                                .append(" >/dev/null 2>&1\n");
                    }
                }
            }
        }
    }

    private JsonNode runtimeContext(JsonNode workspace, JsonNode profile) {
        String requested = profile == null ? "" : profile.path("runtimeContextName").asText("");
        JsonNode fallback = null;
        for (JsonNode context : workspace.path("runtimeContexts")) {
            if (requested.equals(context.path("name").asText(""))) {
                return context;
            }
            if (fallback == null && context.path("autoStart").asBoolean(false)) {
                fallback = context;
            }
            if (fallback == null) {
                fallback = context;
            }
        }
        return fallback;
    }
}
