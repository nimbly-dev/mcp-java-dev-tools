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
        if (runtime.dynamicEnabled(options, profile)) {
            script.append("if ($env:MCPJVM_PORTABLE_RUNTIME_ACTIVE -ne '1') {\n");
            scripts.append(script, scriptInvocations, "preRuntime");
            script.append("& node (Join-Path $PSScriptRoot 'run-portable-sidecar-lifecycle.js') ")
                    .append("--config (Join-Path $PSScriptRoot 'portable-sidecar-attach.config.json') ")
                    .append("--action run --mode ps1 --shell (Get-Process -Id $PID).Path --script $PSCommandPath\n")
                    .append("exit $LASTEXITCODE\n}\n");
        } else {
            scripts.append(script, scriptInvocations, "preRuntime");
        }
        runtime.appendPowerShellRuntime(script, options, profile);
        scripts.append(script, scriptInvocations, "postRuntime");
        runtime.appendPowerShellHealthchecks(script, options.workspace(), options.includeHealthcheckGate());
        scripts.append(script, scriptInvocations, "postHealthcheck");
        workload.append(script, selectedWorkload, scriptInvocations);
        return script.toString();
    }
}
