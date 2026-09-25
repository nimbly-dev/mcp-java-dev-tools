package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import org.springframework.lang.Nullable;

/** Structured outcome for describing one canonical operation ID. */
public record OperationDescribeMcpResult(
        String status,
        String reasonCode,
        String reason,
        @Nullable OperationMcpDetails operation) {
}
