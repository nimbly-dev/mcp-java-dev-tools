package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.DefaultArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement.ArtifactManagementMcpRequestMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement.ArtifactManagementMcpResponseMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement.ArtifactManagementMcpTool;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement.ArtifactManagementMcpSchemaPostProcessor;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the complete Artifact Management Core Feature. */
@Configuration
public class ArtifactManagementConfiguration {

    @Bean
    ArtifactWorkspaceProvider artifactWorkspaceProvider(WorkspaceContext context) {
        return () -> {
            WorkspaceSnapshot snapshot = context.snapshot();
            return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.root());
        };
    }

    @Bean
    ObjectMapper artifactManagementObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    ArtifactJsonStore artifactJsonStore(ObjectMapper mapper) {
        return new ArtifactJsonStore(mapper);
    }

    @Bean
    SqliteRunStateStore sqliteRunStateStore(ObjectMapper mapper) {
        return new SqliteRunStateStore(mapper);
    }

    @Bean
    ArtifactManagementSupport artifactManagementSupport(
            ArtifactWorkspaceProvider workspaceProvider,
            ArtifactJsonStore jsonStore,
            SqliteRunStateStore runStateStore,
            ObjectMapper mapper) {
        return new ArtifactManagementSupport(workspaceProvider, jsonStore, runStateStore, mapper);
    }

    @Bean
    ProbeConfigOperations probeConfigOperations(
            ArtifactManagementSupport support,
            WorkspaceProbeRegistrySource source) {
        return new ProbeConfigOperations(support, source);
    }

    @Bean
    ProjectContextOperations projectContextOperations(ArtifactManagementSupport support) {
        return new ProjectContextOperations(support);
    }

    @Bean
    PlanOperations planOperations(ArtifactManagementSupport support) {
        return new PlanOperations(support);
    }

    @Bean
    RunResultOperations runResultOperations(ArtifactManagementSupport support) {
        return new RunResultOperations(support);
    }

    @Bean
    ExecutionExportOperations executionExportOperations(ArtifactManagementSupport support) {
        return new ExecutionExportOperations(support);
    }

    @Bean
    ArtifactOperationCatalog artifactOperationCatalog(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            RunResultOperations runs,
            ExecutionExportOperations exports) {
        return new ArtifactOperationCatalog(
                probe, project, plans, runs, exports,
                ArtifactManagementMcpTool.operationExposure(),
                new OperationTraceMetadata(
                        ArtifactManagementMcpTool.class.getName(),
                        ArtifactManagementMcpRequestMapper.class.getName(),
                        ArtifactManagementFeature.class.getName(),
                        ArtifactManagementMcpResponseMapper.class.getName(),
                        "mcp-server/core/src/test/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                                + "artifactmanagement/ArtifactManagementFeatureRoutingTest.java",
                        "artifact_management",
                        Map.of("artifactSupport", ArtifactManagementSupport.class.getName())));
    }

    @Bean
    ArtifactManagementFeature artifactManagementFeature(ArtifactOperationCatalog operationCatalog) {
        return new DefaultArtifactManagementFeature(operationCatalog);
    }

    @Bean
    static ArtifactManagementMcpSchemaPostProcessor artifactManagementMcpSchemaPostProcessor() {
        return new ArtifactManagementMcpSchemaPostProcessor();
    }
}
