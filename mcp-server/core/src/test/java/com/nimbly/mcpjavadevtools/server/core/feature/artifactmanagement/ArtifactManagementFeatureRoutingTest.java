package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves the complete Artifact family/action route through the real Feature. */
class ArtifactManagementFeatureRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ArtifactManagementFeature feature;

    @BeforeEach
    void setUp() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                Optional::empty,
                new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(mapper),
                mapper);
        feature = new DefaultArtifactManagementFeature(
                new ProbeConfigOperations(support),
                new ProjectContextOperations(support),
                new PlanOperations(support),
                new RunResultOperations(support),
                new ExecutionExportOperations(support));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ArtifactManagementAction.class)
    void everyAllowedPairReachesItsDeclaredFamilyOwner(ArtifactManagementAction action) {
        ArtifactManagementRequest request = new ArtifactManagementRequest(
                action.artifactType(), action.action(), mapper.createObjectNode());

        ArtifactManagementResult result = feature.execute(request);

        assertThat(result.reasonCode())
                .as("route for %s", action.routeId())
                .isEqualTo("workspace_context_missing");
    }

    @Test
    void unsupportedFamilyActionIsBlockedBeforeExecution() {
        ArtifactManagementResult result = feature.execute(new ArtifactManagementRequest(
                ArtifactType.PROBE_CONFIG, ArtifactAction.QUERY, mapper.createObjectNode()));

        assertThat(result.reasonCode()).isEqualTo("artifact_action_not_allowed");
    }

    @Test
    void nullRequestIsBlockedDeterministically() {
        assertThat(feature.execute(null).reasonCode()).isEqualTo("artifact_management_request_invalid");
    }
}
