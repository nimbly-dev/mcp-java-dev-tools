package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Path;

/** Coordinates manifest, replay, and result assembly for one export package. */
final class ExecutionExportPackageWriter {

    private final ArtifactManagementSupport support;
    private final ExecutionExportManifestWriter manifestWriter;
    private final ExecutionExportReplayWriter replayWriter;
    private final ExecutionExportResultAssembler resultAssembler;

    ExecutionExportPackageWriter(ArtifactManagementSupport support) {
        this.support = support;
        manifestWriter = new ExecutionExportManifestWriter(support);
        replayWriter = new ExecutionExportReplayWriter(support);
        resultAssembler = new ExecutionExportResultAssembler(support);
    }

    ArtifactManagementResult write(
            ArtifactManagementRequest request,
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options) {
        String exportId = ExecutionExportId.resolve(request, projectName);
        Path export = workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId);
        JsonNode profile = ExecutionExportProfileSelector.select(
                options.workspace(), workload.executionProfile());
        ExecutionExportContext context = new ExecutionExportContext(
                workspace, projectName, exportId, export, mode, workload, options, profile);
        support.jsonStore().write(
                workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId, "manifest.json"),
                manifestWriter.build(exportId, projectName, mode, workload, options));
        replayWriter.write(context);
        return resultAssembler.create(request, context);
    }
}
