package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/**
 * Approved Artifact boundary for execution-profile export generation.
 *
 * <p>The execution-profile Feature uses this narrow contract instead of
 * reaching into Artifact Management implementation details.</p>
 */
public interface ExecutionExportArtifactGateway {

    /** Generates and persists one deterministic execution export. */
    ArtifactManagementResult generate(ArtifactManagementRequest request);
}
