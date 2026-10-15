package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Appends prepared project script invocations to a POSIX shell replay. */
final class ExecutionExportShellScripts {

    void append(StringBuilder script, java.util.List<ExecutionExportScriptInvocation> scripts, String phase) {
        for (ExecutionExportScriptInvocation invocation : scripts) {
            if (!phase.equals(invocation.phase())) {
                continue;
            }
            script.append("echo ").append(ExecutionExportShellQuoting.shellSingleQuoted(
                    "[S] " + invocation.phase() + " " + invocation.name())).append("\n")
                    .append("(");
            invocation.env().forEach((key, value) -> script.append("export ")
                    .append(key).append("=")
                    .append(ExecutionExportShellQuoting.shellSingleQuoted(value)).append("; "));
            if (!invocation.appdir().isBlank()) {
                script.append("mkdir -p \"$__MCPJVM_EXPORT_ROOT\"/")
                        .append(ExecutionExportShellQuoting.shellSingleQuoted(invocation.appdir()))
                        .append(" && cd \"$__MCPJVM_EXPORT_ROOT\"/")
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
