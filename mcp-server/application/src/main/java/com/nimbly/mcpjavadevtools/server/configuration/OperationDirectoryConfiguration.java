package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionRunDirectoryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.TrustedDirectSuiteRun;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.CatalogQueryPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectoryOwners;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.TrustedCoreOperationDirectory;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the one aggregate Core operation directory. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationCatalogConfigurationProperties.class)
public class OperationDirectoryConfiguration {

    @Bean
    CatalogQueryPolicy catalogQueryPolicy(OperationCatalogConfigurationProperties properties) {
        return new CatalogQueryPolicy(properties.getDefaultLimit(), properties.getMaximumLimit());
    }

    @Bean
    ArtifactOperationOwners artifactOperationOwners(
            ProbeConfigOperations probeConfigOperations,
            ProjectContextOperations projectContextOperations,
            PlanOperations planOperations,
            RunResultOperations runResultOperations,
            ExecutionExportOperations executionExportOperations) {
        return new ArtifactOperationOwners(
                probeConfigOperations, projectContextOperations, planOperations,
                runResultOperations, executionExportOperations);
    }

    @Bean
    DirectOperationOwners directOperationOwners(
            List<JvmLifecycleActionHandler> jvmLifecycleHandlers,
            List<ProbeActionHandler> probeHandlers,
            ExecutionExportArtifactGateway export,
            RouteSynthesisFeature routes,
            List<FailureAnalysisActionHandler> failureAnalysisHandlers) {
        return new DirectOperationOwners(
                jvmLifecycleHandlers, probeHandlers, export, routes, failureAnalysisHandlers);
    }

    @Bean
    CoreFeatureOwners coreFeatureOwners(
            TransportExecutionFeature transport,
            List<ExecutionOrchestrationActionHandler> orchestrationHandlers,
            RegressionSuiteFeature regression,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security) {
        return new CoreFeatureOwners(transport, orchestrationHandlers, regression, performance, security);
    }

    @Bean
    CoreOperationDirectoryOwners coreOperationDirectoryOwners(
            ArtifactOperationOwners artifactOwners,
            DirectOperationOwners directOwners,
            CoreFeatureOwners featureOwners) {
        return new CoreOperationDirectoryOwners(
                artifactOwners.probeConfigOperations(),
                artifactOwners.projectContextOperations(),
                artifactOwners.planOperations(),
                artifactOwners.runResultOperations(),
                artifactOwners.executionExportOperations(),
                directOwners.jvmLifecycleHandlers(),
                directOwners.probeHandlers(),
                directOwners.export(),
                directOwners.routes(),
                directOwners.failureAnalysisHandlers(),
                featureOwners.transport(),
                featureOwners.orchestrationHandlers(),
                featureOwners.regression(),
                featureOwners.performance(),
                featureOwners.security());
    }

    @Bean
    TrustedSuiteExecution trustedSuiteExecution(
            ArtifactManagementFeature artifacts,
            ExecutionRunDirectoryProvider directories,
            ObjectMapper mapper) {
        return new TrustedDirectSuiteRun(artifacts, directories, mapper);
    }

    @Bean
    CoreOperationDirectory coreOperationDirectory(
            CoreOperationDirectoryOwners owners,
            ObjectMapper mapper,
            TrustedSuiteExecution trustedSuiteExecution) {
        return TrustedCoreOperationDirectory.create(owners, mapper, trustedSuiteExecution);
    }

    @Bean
    OperationDirectory operationDirectory(CoreOperationDirectory coreOperationDirectory) {
        return coreOperationDirectory.directory();
    }

    private record ArtifactOperationOwners(
            ProbeConfigOperations probeConfigOperations,
            ProjectContextOperations projectContextOperations,
            PlanOperations planOperations,
            RunResultOperations runResultOperations,
            ExecutionExportOperations executionExportOperations) {
    }

    private record DirectOperationOwners(
            List<JvmLifecycleActionHandler> jvmLifecycleHandlers,
            List<ProbeActionHandler> probeHandlers,
            ExecutionExportArtifactGateway export,
            RouteSynthesisFeature routes,
            List<FailureAnalysisActionHandler> failureAnalysisHandlers) {
    }

    private record CoreFeatureOwners(
            TransportExecutionFeature transport,
            List<ExecutionOrchestrationActionHandler> orchestrationHandlers,
            RegressionSuiteFeature regression,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security) {
    }
}
