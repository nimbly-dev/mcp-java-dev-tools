package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

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
            ExecutionExportContext context,
            List<ExecutionExportScriptInvocation> scripts) {
        return switch (context.mode()) {
            case "ps1" -> powerShell.render(context.workload(), context.options(), context.profile(), scripts);
            case "sh" -> shell.render(context.workload(), context.options(), context.profile(), scripts);
            case "postman" -> postman.render(context, scripts);
            default -> throw new ArtifactOperationException(
                    "execution_export_mode_invalid", "export mode is unsupported");
        };
    }
}
