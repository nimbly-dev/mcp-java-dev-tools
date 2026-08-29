package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.List;

/** Selects the format-specific renderer for an execution-export replay. */
final class ExecutionExportReplayRenderer {

    private final ExecutionExportPowerShellRenderer powerShell;
    private final ExecutionExportShellRenderer shell;
    private final ExecutionExportPostmanRenderer postman;

    ExecutionExportReplayRenderer(ArtifactManagementSupport support) {
        ExecutionExportRuntimeRenderer runtime = new ExecutionExportRuntimeRenderer();
        powerShell = new ExecutionExportPowerShellRenderer(runtime);
        shell = new ExecutionExportShellRenderer(runtime);
        postman = new ExecutionExportPostmanRenderer(support);
    }

    String render(
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ExecutionExportScriptInvocation> scripts) {
        return switch (mode) {
            case "ps1" -> powerShell.render(workload, options, profile, scripts);
            case "sh" -> shell.render(workload, options, profile, scripts);
            case "postman" -> postman.render(workload, options);
            default -> throw new ArtifactOperationException(
                    "execution_export_mode_invalid", "export mode is unsupported");
        };
    }
}
