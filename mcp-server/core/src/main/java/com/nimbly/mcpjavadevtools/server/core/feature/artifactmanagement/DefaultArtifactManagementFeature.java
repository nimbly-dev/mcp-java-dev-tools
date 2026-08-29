package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import java.util.Map;
import java.util.Objects;

/** Complete production Artifact Management Feature implementation. */
public final class DefaultArtifactManagementFeature implements ArtifactManagementFeature {

    private final ArtifactOperationCatalog operationCatalog;

    /** Creates the Feature from the complete capability-owned operation catalog. */
    public DefaultArtifactManagementFeature(ArtifactOperationCatalog operationCatalog) {
        this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog must not be null");
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest request) {
        if (request == null || request.artifactType() == null || request.action() == null) {
            return ArtifactManagementResult.blocked(
                    "artifact_management_request_invalid",
                    "artifactType and action are required",
                    Map.of("failedStep", "input_validation"));
        }
        return ArtifactManagementAction.resolve(request.artifactType(), request.action())
                .map(operationCatalogAction -> operationCatalog.execute(operationCatalogAction, request))
                .orElseGet(() -> ArtifactManagementResult.blocked(
                        "artifact_action_not_allowed",
                        "requested action is not permitted for the Artifact family",
                        Map.of(
                                "artifactType", request.artifactType().value(),
                                "action", request.action().value(),
                                "allowedActions", ArtifactManagementAction.allowedActions(request.artifactType()))));
    }
}
