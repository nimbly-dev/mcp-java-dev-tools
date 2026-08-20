package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.DefaultArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.ArtifactManagementActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.BackfillRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.CleanupRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.CutoverRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.GenerateExecutionExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListExecutionExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListProjectContextAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListPerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ListSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.QueryRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadExecutionExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadProbeConfigAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadProjectContextAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadPerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReadSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.RebuildRunResultAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ReloadProbeConfigAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.UpsertProbeConfigAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.UpsertProjectContextAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.UpsertPerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.UpsertRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.UpsertSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ValidateProbeConfigAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ValidateProjectContextAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ValidatePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ValidateRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.action.impl.ValidateSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement.ArtifactManagementMcpSchemaPostProcessor;
import java.util.List;
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
    ProbeConfigArtifacts probeConfigArtifacts(
            ArtifactManagementSupport support,
            WorkspaceProbeRegistrySource source) {
        return new ProbeConfigArtifacts(support, source);
    }

    @Bean
    ProjectContextArtifacts projectContextArtifacts(ArtifactManagementSupport support) {
        return new ProjectContextArtifacts(support);
    }

    @Bean
    PlanArtifacts planArtifacts(ArtifactManagementSupport support) {
        return new PlanArtifacts(support);
    }

    @Bean
    RunResultArtifacts runResultArtifacts(ArtifactManagementSupport support) {
        return new RunResultArtifacts(support);
    }

    @Bean
    ExecutionExportArtifacts executionExportArtifacts(ArtifactManagementSupport support) {
        return new ExecutionExportArtifacts(support);
    }

    @Bean
    ReadProbeConfigAction readProbeConfigAction(ProbeConfigArtifacts artifacts) {
        return new ReadProbeConfigAction(artifacts);
    }

    @Bean
    ValidateProbeConfigAction validateProbeConfigAction(ProbeConfigArtifacts artifacts) {
        return new ValidateProbeConfigAction(artifacts);
    }

    @Bean
    UpsertProbeConfigAction upsertProbeConfigAction(ProbeConfigArtifacts artifacts) {
        return new UpsertProbeConfigAction(artifacts);
    }

    @Bean
    ReloadProbeConfigAction reloadProbeConfigAction(ProbeConfigArtifacts artifacts) {
        return new ReloadProbeConfigAction(artifacts);
    }

    @Bean
    ReadProjectContextAction readProjectContextAction(ProjectContextArtifacts artifacts) {
        return new ReadProjectContextAction(artifacts);
    }

    @Bean
    ValidateProjectContextAction validateProjectContextAction(ProjectContextArtifacts artifacts) {
        return new ValidateProjectContextAction(artifacts);
    }

    @Bean
    UpsertProjectContextAction upsertProjectContextAction(ProjectContextArtifacts artifacts) {
        return new UpsertProjectContextAction(artifacts);
    }

    @Bean
    ListProjectContextAction listProjectContextAction(ProjectContextArtifacts artifacts) {
        return new ListProjectContextAction(artifacts);
    }

    @Bean
    ReadPerformancePlanAction readPerformancePlanAction(PlanArtifacts artifacts) {
        return new ReadPerformancePlanAction(artifacts);
    }

    @Bean
    ValidatePerformancePlanAction validatePerformancePlanAction(PlanArtifacts artifacts) {
        return new ValidatePerformancePlanAction(artifacts);
    }

    @Bean
    UpsertPerformancePlanAction upsertPerformancePlanAction(PlanArtifacts artifacts) {
        return new UpsertPerformancePlanAction(artifacts);
    }

    @Bean
    ListPerformancePlanAction listPerformancePlanAction(PlanArtifacts artifacts) {
        return new ListPerformancePlanAction(artifacts);
    }

    @Bean
    ReadRegressionPlanAction readRegressionPlanAction(PlanArtifacts artifacts) {
        return new ReadRegressionPlanAction(artifacts);
    }

    @Bean
    ValidateRegressionPlanAction validateRegressionPlanAction(PlanArtifacts artifacts) {
        return new ValidateRegressionPlanAction(artifacts);
    }

    @Bean
    UpsertRegressionPlanAction upsertRegressionPlanAction(PlanArtifacts artifacts) {
        return new UpsertRegressionPlanAction(artifacts);
    }

    @Bean
    ListRegressionPlanAction listRegressionPlanAction(PlanArtifacts artifacts) {
        return new ListRegressionPlanAction(artifacts);
    }

    @Bean
    ReadSecurityPlanAction readSecurityPlanAction(PlanArtifacts artifacts) {
        return new ReadSecurityPlanAction(artifacts);
    }

    @Bean
    ValidateSecurityPlanAction validateSecurityPlanAction(PlanArtifacts artifacts) {
        return new ValidateSecurityPlanAction(artifacts);
    }

    @Bean
    UpsertSecurityPlanAction upsertSecurityPlanAction(PlanArtifacts artifacts) {
        return new UpsertSecurityPlanAction(artifacts);
    }

    @Bean
    ListSecurityPlanAction listSecurityPlanAction(PlanArtifacts artifacts) {
        return new ListSecurityPlanAction(artifacts);
    }

    @Bean
    ReadRunResultAction readRunResultAction(RunResultArtifacts artifacts) {
        return new ReadRunResultAction(artifacts);
    }

    @Bean
    ListRunResultAction listRunResultAction(RunResultArtifacts artifacts) {
        return new ListRunResultAction(artifacts);
    }

    @Bean
    RebuildRunResultAction rebuildRunResultAction(RunResultArtifacts artifacts) {
        return new RebuildRunResultAction(artifacts);
    }

    @Bean
    BackfillRunResultAction backfillRunResultAction(RunResultArtifacts artifacts) {
        return new BackfillRunResultAction(artifacts);
    }

    @Bean
    CutoverRunResultAction cutoverRunResultAction(RunResultArtifacts artifacts) {
        return new CutoverRunResultAction(artifacts);
    }

    @Bean
    QueryRunResultAction queryRunResultAction(RunResultArtifacts artifacts) {
        return new QueryRunResultAction(artifacts);
    }

    @Bean
    CleanupRunResultAction cleanupRunResultAction(RunResultArtifacts artifacts) {
        return new CleanupRunResultAction(artifacts);
    }

    @Bean
    ReadExecutionExportAction readExecutionExportAction(ExecutionExportArtifacts artifacts) {
        return new ReadExecutionExportAction(artifacts);
    }

    @Bean
    ListExecutionExportAction listExecutionExportAction(ExecutionExportArtifacts artifacts) {
        return new ListExecutionExportAction(artifacts);
    }

    @Bean
    GenerateExecutionExportAction generateExecutionExportAction(ExecutionExportArtifacts artifacts) {
        return new GenerateExecutionExportAction(artifacts);
    }

    @Bean
    ArtifactManagementFeature artifactManagementFeature(List<ArtifactManagementActionHandler> handlers) {
        return new DefaultArtifactManagementFeature(handlers);
    }

    @Bean
    static ArtifactManagementMcpSchemaPostProcessor artifactManagementMcpSchemaPostProcessor() {
        return new ArtifactManagementMcpSchemaPostProcessor();
    }
}
