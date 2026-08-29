package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.util.List;

/** Validates export modes and owns their replay-file conventions. */
final class ExecutionExportMode {

    private ExecutionExportMode() {
    }

    static String resolve(ArtifactManagementRequest request) {
        String mode = request.text("mode").or(() -> request.text("type")).orElseThrow(
                () -> new ArtifactOperationException("execution_export_mode_required", "mode is required"));
        if (request.text("mode").isPresent() && request.text("type").isPresent()
                && !request.text("mode").get().equals(request.text("type").get())) {
            throw new ArtifactOperationException("execution_export_mode_conflict", "mode and type alias must match");
        }
        if (!List.of("ps1", "sh", "postman").contains(mode)) {
            throw new ArtifactOperationException("execution_export_mode_invalid", "export mode is unsupported");
        }
        return mode;
    }

    static String replayFileName(String mode) {
        return switch (mode) {
            case "ps1" -> "replay.ps1";
            case "sh" -> "replay.sh";
            default -> "replay.postman.json";
        };
    }

    static String performanceRunner(String mode) {
        if ("ps1".equals(mode)) {
            return "\n& node (Join-Path $PSScriptRoot 'run-performance-profile.js') "
                    + "--bundle (Join-Path $PSScriptRoot 'performance-export.bundle.json') "
                    + "--env-file (Join-Path $PSScriptRoot 'project.env') "
                    + "--export-dir $PSScriptRoot\n";
        }
        if ("sh".equals(mode)) {
            return "\nnode \"$PWD/run-performance-profile.js\" --bundle \"$PWD/performance-export.bundle.json\" "
                    + "--env-file \"$PWD/project.env\" --export-dir \"$PWD\"\n";
        }
        return "";
    }
}
