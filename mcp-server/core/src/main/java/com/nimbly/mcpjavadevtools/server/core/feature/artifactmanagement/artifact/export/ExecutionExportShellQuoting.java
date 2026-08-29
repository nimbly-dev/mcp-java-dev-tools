package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Applies shell-specific quoting to values and exported script arguments. */
final class ExecutionExportShellQuoting {

    private ExecutionExportShellQuoting() {
    }

    static String powerShellArgument(String value) {
        if ("__MCPJVM_PROJECT_ENV__".equals(value)) {
            return "$__mcpjvm_env_file";
        }
        if (value.startsWith("__MCPJVM_SCRIPT__")) {
            return "(Join-Path $PSScriptRoot "
                    + powerShellSingleQuoted(value.substring("__MCPJVM_SCRIPT__".length())) + ")";
        }
        return powerShellSingleQuoted(value);
    }

    static String shellArgument(String value) {
        if ("__MCPJVM_PROJECT_ENV__".equals(value)) {
            return "\"$__MCPJVM_PROJECT_ENV\"";
        }
        if (value.startsWith("__MCPJVM_SCRIPT__")) {
            return "\"$PWD/" + value.substring("__MCPJVM_SCRIPT__".length()) + "\"";
        }
        return shellSingleQuoted(value);
    }

    static String powerShellSingleQuoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String shellSingleQuoted(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
