package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.DefaultExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.impl.ExecuteExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.ExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.FileExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.ExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionRunDirectoryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.lifecycle.FileExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.lifecycle.ApplicationExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionorchestration.ExecutionOrchestrationMcpSchemaPostProcessor;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the public execution-orchestration Core Feature. */
@Configuration
public class ExecutionOrchestrationConfiguration {

    @Bean
    ExecuteExecutionOrchestrationAction executeExecutionOrchestrationAction(
            ExecutionOrchestrationDependencies dependencies,
            ExecutionRunDirectoryProvider runDirectories,
            ExecutionRunLease lease,
            ExecutionSuiteStateStore suiteState,
            ExecutionRuntimeLifecycle runtimeLifecycle) {
        return new ExecuteExecutionOrchestrationAction(
                dependencies.artifacts(), dependencies.performance(), dependencies.security(), dependencies.regression(),
                dependencies.mapper(), runDirectories, lease, suiteState, runtimeLifecycle);
    }

    @Bean
    ExecutionOrchestrationDependencies executionOrchestrationDependencies(
            ArtifactManagementFeature artifacts,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security,
            RegressionSuiteFeature regression,
            ObjectMapper mapper) {
        return new ExecutionOrchestrationDependencies(artifacts, performance, security, regression, mapper);
    }

    @Bean
    ExecutionRunLease executionRunLease(WorkspaceContext context) {
        Path workspaceRoot = context.snapshot().root();
        return new FileExecutionRunLease(workspaceRoot == null ? Path.of("") : workspaceRoot);
    }

    @Bean
    ExecutionRunDirectoryProvider executionRunDirectoryProvider(WorkspaceContext context) {
        return (projectName, suiteType, planName, runId) -> runDirectory(context.snapshot(), projectName, suiteType, planName, runId);
    }

    @Bean
    ExecutionSuiteStateStore executionSuiteStateStore(WorkspaceContext context, ObjectMapper mapper) {
        return new FileExecutionSuiteStateStore(context, mapper);
    }

    @Bean
    ExecutionRuntimeLifecycle executionRuntimeLifecycle(
            JvmLifecycleFeature jvms, ProbeFeature probe, ObjectMapper mapper) {
        return new ApplicationExecutionRuntimeLifecycle(jvms, probe, mapper);
    }

    private static Optional<String> runDirectory(
            WorkspaceSnapshot workspace, String projectName, String suiteType, String planName, String runId) {
        if (workspace == null || workspace.root() == null) {
            return Optional.empty();
        }
        return Optional.of(workspace.root().resolve(".mcpjvm").resolve(projectName).resolve("plans")
                .resolve(suiteType).resolve(planName).resolve("runs").resolve(runId).toString());
    }

    private record ExecutionOrchestrationDependencies(
            ArtifactManagementFeature artifacts,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security,
            RegressionSuiteFeature regression,
            ObjectMapper mapper) {
    }

    @Bean
    ExecutionOrchestrationFeature executionOrchestrationFeature(
            List<ExecutionOrchestrationActionHandler> handlers) {
        return new DefaultExecutionOrchestrationFeature(handlers);
    }

    @Bean
    static ExecutionOrchestrationMcpSchemaPostProcessor executionOrchestrationMcpSchemaPostProcessor() {
        return new ExecutionOrchestrationMcpSchemaPostProcessor();
    }
}
