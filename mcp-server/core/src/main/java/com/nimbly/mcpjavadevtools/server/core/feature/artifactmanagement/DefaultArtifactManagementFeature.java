package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.List;
import java.util.Map;

/** Complete production Artifact Management Feature implementation. */
public final class DefaultArtifactManagementFeature implements ArtifactManagementFeature {

    private final EnumActionDispatcher<
            ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> dispatcher;

    /** Creates a dispatcher for the complete public family/action allowlist. */
    public DefaultArtifactManagementFeature(List<? extends ArtifactManagementActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(ArtifactManagementAction.class, handlers);
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
                .map(action -> dispatcher.dispatch(action, request))
                .orElseGet(() -> ArtifactManagementResult.blocked(
                        "artifact_action_not_allowed",
                        "requested action is not permitted for the Artifact family",
                        Map.of(
                                "artifactType", request.artifactType().value(),
                                "action", request.action().value(),
                                "allowedActions", ArtifactManagementAction.allowedActions(request.artifactType()))));
    }
}
