package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Concrete security plan_upsert Artifact action. */
public final class UpsertSecurityPlanAction implements ArtifactManagementActionHandler {

    private final PlanArtifacts artifacts;

    /** Creates the action from the purpose-owned Artifact operations. */
    public UpsertSecurityPlanAction(PlanArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactManagementAction action() {
        return ArtifactManagementAction.SECURITY_PLAN_UPSERT;
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        return artifacts.upsert(request, "security");
    }
}
