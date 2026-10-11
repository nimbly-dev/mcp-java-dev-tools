package com.nimbly.mcpjavadevtools.server.core.operation.composition;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.util.List;

/** Immutable Core-owned capability set used to assemble the 610 directory. */
public record CoreOperationDirectoryOwners(
        ProbeConfigOperations probeConfigOperations,
        ProjectContextOperations projectContextOperations,
        PlanOperations planOperations,
        RunResultOperations runResultOperations,
        ExecutionExportOperations executionExportOperations,
        List<JvmLifecycleActionHandler> jvmLifecycleHandlers,
        List<ProbeActionHandler> probeHandlers,
        ExecutionExportArtifactGateway export,
        RouteSynthesisFeature routes,
        List<FailureAnalysisActionHandler> failureAnalysisHandlers,
        TransportExecutionFeature transport,
        List<ExecutionOrchestrationActionHandler> orchestrationHandlers,
        RegressionSuiteFeature regression,
        PerformanceSuiteFeature performance,
        SecuritySuiteFeature security) {

    public CoreOperationDirectoryOwners {
        jvmLifecycleHandlers = List.copyOf(jvmLifecycleHandlers);
        probeHandlers = List.copyOf(probeHandlers);
        failureAnalysisHandlers = List.copyOf(failureAnalysisHandlers);
        orchestrationHandlers = List.copyOf(orchestrationHandlers);
    }
}
