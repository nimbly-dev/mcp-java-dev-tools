package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.nio.file.Path;

/** Immutable state shared by the export package writers. */
record ExecutionExportContext(
        ArtifactManagementSupport.Workspace workspace,
        String projectName,
        String exportId,
        Path export,
        String mode,
        ExecutionExportWorkload.Workload workload,
        ExecutionExportOptions options,
        JsonNode profile) {
}
