package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies that descriptor ownership cannot self-authorize the executable owner. */
class ArtifactOperationTest {

    private static final OperationTraceMetadata TRACE = new OperationTraceMetadata(
            "ArtifactManagementMcpTool",
            "ArtifactManagementMcpRequestMapper",
            "ArtifactManagementFeature",
            "ArtifactManagementMcpResponseMapper",
            "ArtifactOperationTest",
            "filesystem_read",
            Map.of("artifactSupport", "ArtifactManagementSupport"));

    @Test
    void rejectsDescriptorOwnerThatDisagreesWithInjectedFamilyOwner() {
        ObjectMapper mapper = new ObjectMapper();
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                Optional::empty,
                new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(mapper),
                mapper);
        ProbeConfigOperations owner = new ProbeConfigOperations(support);
        ArtifactManagementAction action = ArtifactManagementAction.PROBE_CONFIG_READ;
        ArtifactOperation operation = new ArtifactOperation(
                action,
                String.class,
                owner,
                "read",
                owner::read,
                TRACE);
        OperationExposure exposure = new OperationExposure(
                ArtifactOperationCatalog.TOOL_NAME,
                "ArtifactManagementMcpTool",
                List.of(action.routeId()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        ArtifactManagementAction.class,
                        exposure,
                        ArtifactOperationCatalog.class,
                        List.of(operation)))
                .withMessage("descriptor executable owner does not match operation: PROBE_CONFIG_READ");
    }
}
