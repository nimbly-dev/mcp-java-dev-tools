package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Concrete execution export_read Artifact action. */
public final class ReadExecutionExportAction implements ArtifactManagementActionHandler {

    private final ExecutionExportArtifacts artifacts;

    /** Creates the action from the purpose-owned Artifact operations. */
    public ReadExecutionExportAction(ExecutionExportArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactManagementAction action() {
        return ArtifactManagementAction.EXECUTION_EXPORT_READ;
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        return artifacts.read(request);
    }
}
