package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Orchestrates export input resolution and package generation. */
final class ExecutionExportGenerator {

    private final ArtifactManagementSupport support;
    private final ExecutionExportWorkloadResolver workloadResolver;
    private final ExecutionExportPackageWriter packageWriter;

    ExecutionExportGenerator(ArtifactManagementSupport support) {
        this.support = support;
        workloadResolver = new ExecutionExportWorkloadResolver(support);
        packageWriter = new ExecutionExportPackageWriter(support);
    }

    ArtifactManagementResult generate(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String mode = ExecutionExportMode.resolve(request);
            String projectName = support.resolveProject(workspace, request);
            JsonNode projectArtifact = support.jsonStore().read(
                    workspace.paths().resolve(".mcpjvm", projectName, "projects.json"));
            ExecutionExportOptions options = ExecutionExportOptions.resolve(projectArtifact, request);
            ExecutionExportWorkload.Workload workload = workloadResolver.resolve(
                    workspace, projectName, projectArtifact, request, options, mode);
            return packageWriter.write(request, workspace, projectName, mode, workload, options);
        });
    }
}
