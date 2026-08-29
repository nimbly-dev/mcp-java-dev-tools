package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Builds the common PowerShell preamble for exported replay scripts. */
final class ExecutionExportPowerShellHeader {

    String render(ExecutionExportOptions options) {
        String templatePattern = "'\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}'";
        return new StringBuilder("$ErrorActionPreference = 'Stop'\n")
                .append("# MCPJVM export when=")
                .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                        options.when() == null ? "default" : options.when()))
                .append("\n")
                .append("# includeRuntimeStartup=").append(options.includeRuntimeStartup())
                .append(" includeHealthcheckGate=").append(options.includeHealthcheckGate())
                .append(" includeResolvedSecrets=").append(options.includeResolvedSecrets()).append("\n")
                .append("$__mcpjvm_env_file = Join-Path $PSScriptRoot 'project.env'\n")
                .append("if (Test-Path -LiteralPath $__mcpjvm_env_file) {\n")
                .append("  Get-Content -LiteralPath $__mcpjvm_env_file | ForEach-Object { ")
                .append("if ($_ -match '^(\\w+)=(.*)$') { ")
                .append("[Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('\\\"')) } }\n")
                .append("}\n")
                .append("function Resolve-McpJvmTemplate {\n")
                .append("  param([string]$Value)\n")
                .append("  return [regex]::Replace($Value, ")
                .append(templatePattern)
                .append(", { param($Match) $Value = [Environment]::GetEnvironmentVariable(")
                .append("$Match.Groups[1].Value); if ($null -eq $Value) { '' } else { $Value } })\n")
                .append("}\n\n")
                .toString();
    }
}
