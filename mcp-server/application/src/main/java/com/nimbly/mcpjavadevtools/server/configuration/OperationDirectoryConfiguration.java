package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionRunDirectoryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.TrustedDirectSuiteRun;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectoryOwners;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.TrustedCoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.OperationMcpOutputSchemaPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the one aggregate Core operation directory. */
@Configuration(proxyBeanMethods = false)
public class OperationDirectoryConfiguration {

    @Bean
    static BeanPostProcessor operationMcpOutputSchemaPostProcessor() {
        return new OperationMcpOutputSchemaPostProcessor();
    }

    @Bean
    CoreCatalogOwners coreCatalogOwners(
            ArtifactOperationCatalog artifact,
            JvmLifecycleOperationCatalog jvmLifecycle,
            ProbeOperationCatalog probe,
            ExecutionProfileExportOperationCatalog export,
            RouteSynthesisFeature routes,
            FailureAnalysisFeature failures) {
        return new CoreCatalogOwners(artifact, jvmLifecycle, probe, export, routes, failures);
    }

    @Bean
    CoreFeatureOwners coreFeatureOwners(
            TransportExecutionFeature transport,
            ExecutionOrchestrationFeature orchestration,
            RegressionSuiteFeature regression,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security) {
        return new CoreFeatureOwners(transport, orchestration, regression, performance, security);
    }

    @Bean
    CoreOperationDirectoryOwners coreOperationDirectoryOwners(
            CoreCatalogOwners catalogOwners, CoreFeatureOwners featureOwners) {
        return new CoreOperationDirectoryOwners(
                catalogOwners.artifact(),
                catalogOwners.jvmLifecycle(),
                catalogOwners.probe(),
                catalogOwners.export(),
                catalogOwners.routes(),
                catalogOwners.failures(),
                featureOwners.transport(),
                featureOwners.orchestration(),
                featureOwners.regression(),
                featureOwners.performance(),
                featureOwners.security());
    }

    @Bean
    TrustedSuiteExecution trustedSuiteExecution(
            ArtifactOperationCatalog artifacts,
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

    private record CoreCatalogOwners(
            ArtifactOperationCatalog artifact,
            JvmLifecycleOperationCatalog jvmLifecycle,
            ProbeOperationCatalog probe,
            ExecutionProfileExportOperationCatalog export,
            RouteSynthesisFeature routes,
            FailureAnalysisFeature failures) {
    }

    private record CoreFeatureOwners(
            TransportExecutionFeature transport,
            ExecutionOrchestrationFeature orchestration,
            RegressionSuiteFeature regression,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security) {
    }
}
