package com.nimbly.mcpjavadevtools.server.core.operation.composition;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.util.List;

/** Immutable Core-owned capability set used to assemble the 610 directory. */
public record CoreOperationDirectoryOwners(
        ArtifactOperationCatalog artifact,
        JvmLifecycleOperationCatalog jvmLifecycle,
        ProbeOperationCatalog probe,
        ExecutionExportArtifactGateway export,
        RouteSynthesisFeature routes,
        List<FailureAnalysisActionHandler> failureAnalysisHandlers,
        TransportExecutionFeature transport,
        List<ExecutionOrchestrationActionHandler> orchestrationHandlers,
        RegressionSuiteFeature regression,
        PerformanceSuiteFeature performance,
        SecuritySuiteFeature security) {

    public CoreOperationDirectoryOwners {
        failureAnalysisHandlers = List.copyOf(failureAnalysisHandlers);
        orchestrationHandlers = List.copyOf(orchestrationHandlers);
    }
}
