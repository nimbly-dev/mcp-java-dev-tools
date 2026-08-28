package com.nimbly.mcpjavadevtools.server.mcp.tools.executionorchestration;

import org.jspecify.annotations.Nullable;

/** Public transport request for the single execution_orchestration action. */
public record ExecutionOrchestrationMcpRequest(
        @Nullable String projectName,
        @Nullable String executionProfile,
        @Nullable String suiteRunId,
        @Nullable Integer maxPlansPerCall) {
}
