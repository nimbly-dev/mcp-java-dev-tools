package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Concrete regression plan_list Artifact action. */
public final class ListRegressionPlanAction implements ArtifactManagementActionHandler {

    private final PlanArtifacts artifacts;

    /** Creates the action from the purpose-owned Artifact operations. */
    public ListRegressionPlanAction(PlanArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactManagementAction action() {
        return ArtifactManagementAction.REGRESSION_PLAN_LIST;
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        return artifacts.list(request, "regression");
    }
}
