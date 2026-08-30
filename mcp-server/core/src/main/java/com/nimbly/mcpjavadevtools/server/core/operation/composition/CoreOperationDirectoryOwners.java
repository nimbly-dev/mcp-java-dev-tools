package com.nimbly.mcpjavadevtools.server.core.operation.composition;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;

/** Immutable Core-owned capability set used to assemble the 610 directory. */
public record CoreOperationDirectoryOwners(
        ArtifactOperationCatalog artifact,
        JvmLifecycleOperationCatalog jvmLifecycle,
        ProbeOperationCatalog probe,
        ExecutionProfileExportOperationCatalog export,
        RouteSynthesisFeature routes,
        FailureAnalysisFeature failures,
        TransportExecutionFeature transport,
        ExecutionOrchestrationFeature orchestration,
        RegressionSuiteFeature regression,
        PerformanceSuiteFeature performance,
        SecuritySuiteFeature security) {
}
