package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.Objects;

/** Public Artifact-family owner for execution-export read, list, and generate actions. */
public final class ExecutionExportOperations implements ExecutionExportArtifactGateway {

    private final ExecutionExportReader reader;
    private final ExecutionExportGenerator generator;

    /** Creates the export owner and its purpose-owned collaborators. */
    public ExecutionExportOperations(ArtifactManagementSupport support) {
        ArtifactManagementSupport checkedSupport = Objects.requireNonNull(
                support, "support must not be null");
        reader = new ExecutionExportReader(checkedSupport);
        generator = new ExecutionExportGenerator(checkedSupport);
    }

    /** Reads one export package. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return reader.read(request);
    }

    /** Lists export packages. */
    public ArtifactManagementResult list(ArtifactManagementRequest request) {
        return reader.list(request);
    }

    /** Generates one bounded replay package. */
    @Override
    public ArtifactManagementResult generate(ArtifactManagementRequest request) {
        return generator.generate(request);
    }
}
