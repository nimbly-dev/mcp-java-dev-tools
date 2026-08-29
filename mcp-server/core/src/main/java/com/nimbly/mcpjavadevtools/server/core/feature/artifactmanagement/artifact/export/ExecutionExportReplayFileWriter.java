package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.nio.file.Path;

/** Persists generated replay files using the export failure contract. */
final class ExecutionExportReplayFileWriter {

    private final ArtifactManagementSupport support;

    ExecutionExportReplayFileWriter(ArtifactManagementSupport support) {
        this.support = support;
    }

    void write(Path path, String content) {
        try {
            support.jsonStore().writeText(path, content + "\n");
        } catch (ArtifactOperationException exception) {
            throw new ArtifactOperationException(
                    "execution_export_write_failed", "Execution export could not be persisted");
        }
    }
}
