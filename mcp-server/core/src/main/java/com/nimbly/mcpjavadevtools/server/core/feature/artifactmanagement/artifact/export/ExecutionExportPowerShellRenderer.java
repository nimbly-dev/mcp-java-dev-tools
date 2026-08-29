package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Coordinates PowerShell-specific replay sections. */
final class ExecutionExportPowerShellRenderer {

    private final ExecutionExportPowerShellHeader header;
    private final ExecutionExportRuntimeRenderer runtime;
    private final ExecutionExportPowerShellScripts scripts;
    private final ExecutionExportPowerShellWorkload workload;

    ExecutionExportPowerShellRenderer(ExecutionExportRuntimeRenderer runtime) {
        header = new ExecutionExportPowerShellHeader();
        this.runtime = runtime;
        scripts = new ExecutionExportPowerShellScripts();
        workload = new ExecutionExportPowerShellWorkload();
    }

    String render(
            ExecutionExportWorkload.Workload selectedWorkload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ExecutionExportScriptInvocation> scriptInvocations) {
        StringBuilder script = new StringBuilder(header.render(options));
        runtime.appendPowerShellRuntime(script, options, profile);
        scripts.append(script, scriptInvocations);
        runtime.appendPowerShellHealthchecks(script, options.workspace(), options.includeHealthcheckGate());
        workload.append(script, selectedWorkload);
        return script.toString();
    }
}
