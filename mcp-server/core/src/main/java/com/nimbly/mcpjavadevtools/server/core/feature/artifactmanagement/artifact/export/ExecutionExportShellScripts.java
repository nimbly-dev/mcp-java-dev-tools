package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Appends prepared project script invocations to a POSIX shell replay. */
final class ExecutionExportShellScripts {

    void append(StringBuilder script, java.util.List<ExecutionExportScriptInvocation> scripts) {
        for (ExecutionExportScriptInvocation invocation : scripts) {
            script.append("echo ").append(ExecutionExportShellQuoting.shellSingleQuoted(
                    "[S] " + invocation.phase() + " " + invocation.name())).append("\n")
                    .append("(");
            invocation.env().forEach((key, value) -> script.append("export ")
                    .append(key).append("=")
                    .append(ExecutionExportShellQuoting.shellSingleQuoted(value)).append("; "));
            if (!invocation.appdir().isBlank()) {
                script.append("cd ")
                        .append(ExecutionExportShellQuoting.shellSingleQuoted(invocation.appdir()))
                        .append(" && ");
            }
            script.append(ExecutionExportShellQuoting.shellSingleQuoted(invocation.command()));
            for (String arg : invocation.args()) {
                script.append(" ").append(ExecutionExportShellQuoting.shellArgument(arg));
            }
            script.append(")\n");
        }
    }
}
