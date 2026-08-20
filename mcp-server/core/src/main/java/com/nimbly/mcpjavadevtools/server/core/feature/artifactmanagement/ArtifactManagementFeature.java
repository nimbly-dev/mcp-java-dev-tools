package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Intentional Spring-independent entry point for the artifact_management MCP Tool. */
public interface ArtifactManagementFeature {

    /**
     * Executes one typed Artifact Management action.
     *
     * @param request feature-owned request
     * @return deterministic Artifact result
     */
    ArtifactManagementResult execute(ArtifactManagementRequest request);
}
