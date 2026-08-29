package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Reads and lists persisted execution-export packages. */
final class ExecutionExportReader {

    private final ArtifactManagementSupport support;

    ExecutionExportReader(ArtifactManagementSupport support) {
        this.support = support;
    }

    ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String exportId = request.child("query")
                    .flatMap(node -> optionalText(node, "exportId"))
                    .orElseThrow(() -> new ArtifactOperationException("export_id_required", "exportId is required"));
            ArtifactPathPolicy.validateSegment(exportId);
            Path export = workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId);
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "exportId", exportId,
                    "files", support.jsonStore().files(export)));
        });
    }

    ArtifactManagementResult list(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            Path exports = workspace.paths().resolve(".mcpjvm", projectName, "exports");
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "exportFolders", support.jsonStore().directories(exports)));
        });
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? Optional.of(value.asText().trim()) : Optional.empty();
    }
}
