package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Validates one performance execution-plan Artifact. */
public final class ValidatePerformancePlanAction implements ArtifactManagementActionHandler {
    private final PlanArtifacts artifacts;

    /** Creates the action from plan Artifact behavior. */
    public ValidatePerformancePlanAction(PlanArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactManagementAction action() {
        return ArtifactManagementAction.PERFORMANCE_PLAN_VALIDATE;
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        return artifacts.validate(request, "performance");
    }
}
