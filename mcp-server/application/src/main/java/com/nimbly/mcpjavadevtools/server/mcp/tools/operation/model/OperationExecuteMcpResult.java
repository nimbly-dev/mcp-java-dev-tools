package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import java.util.Map;
import org.springframework.lang.Nullable;

/** Structured, deterministic MCP result for one operation execution. */
public record OperationExecuteMcpResult(
        @Nullable String operationId,
        String status,
        String reasonCode,
        String reason,
        @Nullable Object result,
        Map<String, String> metadata) {

    public OperationExecuteMcpResult {
        result = OperationMcpJsonValue.immutableCopy(result);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
