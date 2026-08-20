package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Concrete project context_upsert Artifact action. */
public final class UpsertProjectContextAction implements ArtifactManagementActionHandler {

    private final ProjectContextArtifacts artifacts;

    /** Creates the action from the purpose-owned Artifact operations. */
    public UpsertProjectContextAction(ProjectContextArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactManagementAction action() {
        return ArtifactManagementAction.PROJECT_CONTEXT_UPSERT;
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        return artifacts.upsert(request);
    }
}
