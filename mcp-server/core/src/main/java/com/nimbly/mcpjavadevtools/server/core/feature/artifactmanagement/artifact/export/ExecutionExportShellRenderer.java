package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Coordinates POSIX shell-specific replay sections. */
final class ExecutionExportShellRenderer {

    private final ExecutionExportRuntimeRenderer runtime;
    private final ExecutionExportShellScripts scripts;
    private final ExecutionExportShellWorkload workload;

    ExecutionExportShellRenderer(ExecutionExportRuntimeRenderer runtime) {
        this.runtime = runtime;
        scripts = new ExecutionExportShellScripts();
        workload = new ExecutionExportShellWorkload();
    }

    String render(
            ExecutionExportWorkload.Workload selectedWorkload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ExecutionExportScriptInvocation> scriptInvocations) {
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\nset -euo pipefail\n")
                .append("# MCPJVM export when=")
                .append(ExecutionExportShellQuoting.shellSingleQuoted(
                        options.when() == null ? "default" : options.when()))
                .append("\n")
                .append("# includeRuntimeStartup=").append(options.includeRuntimeStartup())
                .append(" includeHealthcheckGate=").append(options.includeHealthcheckGate())
                .append(" includeResolvedSecrets=").append(options.includeResolvedSecrets()).append("\n")
                .append("__MCPJVM_PROJECT_ENV=\"$PWD/project.env\"\n")
                .append("if [ -f \"$__MCPJVM_PROJECT_ENV\" ]; then set -a; . \"$__MCPJVM_PROJECT_ENV\"; set +a; fi\n")
                .append("resolve_mcpjvm_template() {\n")
                .append("  local value=\"$1\" key replacement\n")
                .append("  while [[ \"$value\" =~ \\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\} ]]; do\n")
                .append("    key=\"${BASH_REMATCH[1]}\"\n")
                .append("    replacement=\"${!key:-}\"\n")
                .append("    value=\"${value//\\{\\{$key\\}\\}/$replacement}\"\n")
                .append("  done\n  printf '%s' \"$value\"\n}\n\n");
        runtime.appendShellRuntime(script, options, profile);
        scripts.append(script, scriptInvocations);
        runtime.appendShellHealthchecks(script, options.workspace(), options.includeHealthcheckGate());
        workload.append(script, selectedWorkload);
        return script.toString();
    }
}
