package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Internal Artifact execution boundary used by execution orchestration. */
public interface ArtifactManagementFeature {

    /**
     * Executes one typed Artifact Management action.
     *
     * @param request feature-owned request
     * @return deterministic Artifact result
     */
    ArtifactManagementResult execute(ArtifactManagementRequest request);
}
