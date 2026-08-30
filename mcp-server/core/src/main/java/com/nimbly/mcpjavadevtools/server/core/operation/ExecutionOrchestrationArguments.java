package com.nimbly.mcpjavadevtools.server.core.operation;

/** Canonical execution-orchestration arguments without the internal action field. */
public record ExecutionOrchestrationArguments(
        String projectName,
        String executionProfile,
        String suiteRunId,
        Integer maxPlansPerCall) {
}
