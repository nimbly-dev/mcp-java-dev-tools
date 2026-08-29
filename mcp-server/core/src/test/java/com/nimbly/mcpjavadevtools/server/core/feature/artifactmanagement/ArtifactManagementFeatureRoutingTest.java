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
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves the complete Artifact family/action route through the real Feature. */
class ArtifactManagementFeatureRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ArtifactManagementFeature feature;
    private ArtifactOperationCatalog catalog;

    @BeforeEach
    void setUp() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                Optional::empty,
                new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(mapper),
                mapper);
        OperationExposure exposure = new OperationExposure(
                ArtifactOperationCatalog.TOOL_NAME,
                "ArtifactManagementMcpTool",
                Arrays.stream(ArtifactManagementAction.values())
                        .map(ArtifactManagementAction::routeId)
                        .toList());
        catalog = new ArtifactOperationCatalog(
                new ProbeConfigOperations(support),
                new ProjectContextOperations(support),
                new PlanOperations(support),
                new RunResultOperations(support),
                new ExecutionExportOperations(support),
                exposure);
        feature = new DefaultArtifactManagementFeature(catalog);
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
        assertThat(catalog.describe(action).action()).isEqualTo(action.routeId());
    }

    @Test
    void generatedInventoryContainsExactlyTheSevenFamiliesAndThirtyOnePairs() {
        List<String> expectedRoutes = Arrays.stream(ArtifactManagementAction.values())
                .map(ArtifactManagementAction::routeId)
                .toList();

        assertThat(ArtifactType.values()).hasSize(7);
        assertThat(catalog.catalog()).hasSize(31)
                .extracting(OperationDescriptor::action)
                .containsExactlyElementsOf(expectedRoutes);
        assertThat(catalog.traceInventory()).hasSize(31)
                .extracting(OperationTraceEntry::action)
                .containsExactlyElementsOf(expectedRoutes);
        assertThat(catalog.traceInventory())
                .allSatisfy(entry -> assertThat(entry.executableOwner()).contains("#"));
        assertThat(catalog.traceInventory()).extracting(OperationTraceEntry::executableOwner)
                .containsExactly(
                        ProbeConfigOperations.class.getName() + "#read",
                        ProbeConfigOperations.class.getName() + "#validate",
                        ProbeConfigOperations.class.getName() + "#upsert",
                        ProbeConfigOperations.class.getName() + "#reload",
                        ProjectContextOperations.class.getName() + "#read",
                        ProjectContextOperations.class.getName() + "#validate",
                        ProjectContextOperations.class.getName() + "#upsert",
                        ProjectContextOperations.class.getName() + "#list",
                        PlanOperations.class.getName() + "#read(performance)",
                        PlanOperations.class.getName() + "#validate(performance)",
                        PlanOperations.class.getName() + "#upsert(performance)",
                        PlanOperations.class.getName() + "#list(performance)",
                        PlanOperations.class.getName() + "#read(regression)",
                        PlanOperations.class.getName() + "#validate(regression)",
                        PlanOperations.class.getName() + "#upsert(regression)",
                        PlanOperations.class.getName() + "#list(regression)",
                        PlanOperations.class.getName() + "#read(security)",
                        PlanOperations.class.getName() + "#validate(security)",
                        PlanOperations.class.getName() + "#upsert(security)",
                        PlanOperations.class.getName() + "#list(security)",
                        RunResultOperations.class.getName() + "#read",
                        RunResultOperations.class.getName() + "#upsert",
                        RunResultOperations.class.getName() + "#list",
                        RunResultOperations.class.getName() + "#rebuild",
                        RunResultOperations.class.getName() + "#backfill",
                        RunResultOperations.class.getName() + "#cutover",
                        RunResultOperations.class.getName() + "#query",
                        RunResultOperations.class.getName() + "#cleanup",
                        ExecutionExportOperations.class.getName() + "#read",
                        ExecutionExportOperations.class.getName() + "#list",
                        ExecutionExportOperations.class.getName() + "#generate");
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
