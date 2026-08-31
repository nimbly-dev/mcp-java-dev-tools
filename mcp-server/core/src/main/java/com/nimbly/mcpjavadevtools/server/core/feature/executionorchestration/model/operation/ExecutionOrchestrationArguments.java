package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.operation;

/** Canonical execution-orchestration arguments without the internal action field. */
public record ExecutionOrchestrationArguments(
        String projectName,
        String executionProfile,
        String suiteRunId,
        Integer maxPlansPerCall) {
}
