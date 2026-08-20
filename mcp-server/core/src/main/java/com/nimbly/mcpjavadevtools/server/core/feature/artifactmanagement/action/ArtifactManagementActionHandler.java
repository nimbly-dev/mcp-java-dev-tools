package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Typed action contract for one Artifact Management family/action pair. */
public interface ArtifactManagementActionHandler extends ActionHandler<
        ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> {
}
