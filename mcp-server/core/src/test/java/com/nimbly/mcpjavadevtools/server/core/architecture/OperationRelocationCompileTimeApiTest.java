package com.nimbly.mcpjavadevtools.server.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Compile-time guard for the MCPJVM-610 to MCPJVM-613 operation-package map.
 *
 * <p>Every public candidate type is referenced directly so a missing or
 * inaccessible relocated API type fails during test compilation. The package
 * map and boundary test cover the package-private implementation types.</p>
 */
class OperationRelocationCompileTimeApiTest {

    private static final List<Class<?>> BASELINE_OPERATION_API_TYPES = List.of(
            com.nimbly.mcpjavadevtools.server.core.operation.CatalogPage.class,
            com.nimbly.mcpjavadevtools.server.core.operation.CatalogQuery.class,
            com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory.class,
            com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult.class,
            com.nimbly.mcpjavadevtools.server.core.operation.OperationId.class,
            com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation.class,
            com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation.class,
            com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalog.class,
            com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogEntry.class,
            com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogPageBuilder.class,
            com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure.class,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor.class,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration.class,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract.class,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoder.class,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoder.class,
            com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory.class,
            com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectoryOwners.class,
            com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException.class,
            com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionException.class,
            com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus.class,
            com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationInvocationExecution.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationAlias.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationArgumentDocumentation.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptorMetadata.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestAssembler.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader.class,
            com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader.class,
            com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema.class,
            com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas.class,
            com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema.class,
            com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaRules.class,
            com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator.class,
            com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy.class,
            com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy.class,
            com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationValueRedactor.class,
            com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity.class,
            com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry.class,
            com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceInventory.class,
            com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata.class,
            com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.operation.ArtifactOperationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.operation.ExecutionOrchestrationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.operation.ExecutionOrchestrationOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.operation.ExecutionProfileExportArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureAnalyzeArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureExpectedFingerprintArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureInvestigationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureLineHitArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureTerminalArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureVerifyArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation.FailureAnalysisOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation.ProbeHttpArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation.ProbeOperationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation.RouteSynthesisOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.operation.TransportExecuteArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.operation.TransportExecuteOptionsArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.operation.TransportExecutionOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation.RegressionSuiteOperationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation.RegressionSuiteOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.operation.PerformanceSuiteOperationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation.PerformanceSuiteOperationRegistrations.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.operation.SecuritySuiteOperationArguments.class,
            com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation.SecuritySuiteOperationRegistrations.class);

    @Test
    void everyPublicBaselineOperationTypeHasACompileTimeCandidate() {
        assertThat(BASELINE_OPERATION_API_TYPES).hasSize(71);
    }

    @Test
    void featureOwnedRecordCarriersExposeTheirResolvedConstructorAndAccessor() {
        var input = JsonNodeFactory.instance.objectNode();

        assertThat(new com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation
                .RegressionSuiteOperationArguments(input).input()).isNotSameAs(input);
        assertThat(new com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.operation
                .PerformanceSuiteOperationArguments(input).input()).isNotSameAs(input);
        assertThat(new com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.operation
                .SecuritySuiteOperationArguments(input).input()).isNotSameAs(input);
    }
}
