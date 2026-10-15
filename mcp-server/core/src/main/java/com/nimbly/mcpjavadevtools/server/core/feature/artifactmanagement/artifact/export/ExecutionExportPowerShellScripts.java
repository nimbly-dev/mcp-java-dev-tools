package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Appends prepared project script invocations to a PowerShell replay. */
final class ExecutionExportPowerShellScripts {

    void append(StringBuilder script, java.util.List<ExecutionExportScriptInvocation> scripts, String phase) {
        for (ExecutionExportScriptInvocation invocation : scripts) {
            if (!phase.equals(invocation.phase())) {
                continue;
            }
            script.append("Write-Host ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                            "[S] " + invocation.phase() + " " + invocation.name())).append("\n")
                    .append("$__mcpjvm_script_env_file = $__mcpjvm_env_file\n");
            invocation.env().forEach((key, value) -> script.append("$env:").append(key).append(" = ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(value)).append("\n"));
            if (!invocation.appdir().isBlank()) {
                script.append("$__mcpjvm_script_directory = Join-Path $PSScriptRoot ")
                        .append(ExecutionExportShellQuoting.powerShellSingleQuoted(invocation.appdir()))
                        .append("\nNew-Item -ItemType Directory -Path $__mcpjvm_script_directory -Force | Out-Null\n")
                        .append("Push-Location -LiteralPath $__mcpjvm_script_directory\n");
            }
            script.append("& ")
                    .append(ExecutionExportShellQuoting.powerShellSingleQuoted(invocation.command()));
            for (String arg : invocation.args()) {
                script.append(" ").append(ExecutionExportShellQuoting.powerShellArgument(arg));
            }
            script.append("\n$__mcpjvm_script_exit = $LASTEXITCODE\n");
            if (!invocation.appdir().isBlank()) {
                script.append("Pop-Location\n");
            }
            script.append("if ($__mcpjvm_script_exit -ne 0) { throw 'export script failed' }\n");
        }
    }
}
