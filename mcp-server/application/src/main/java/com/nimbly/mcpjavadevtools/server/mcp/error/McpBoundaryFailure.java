package com.nimbly.mcpjavadevtools.server.mcp.error;

import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral description of an unexpected MCP Application boundary failure.
 *
 * @param correlationId bounded operator correlation identity
 * @param failureKind closed internal failure classification
 */
public record McpBoundaryFailure(
        String correlationId,
        McpBoundaryFailureKind failureKind) {

    /** Creates one sanitized boundary failure with a new correlation identity. */
    public McpBoundaryFailure {
        correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
    }

    /**
     * Creates a boundary failure for the supplied internal classification.
     *
     * @param failureKind closed internal failure classification
     * @return sanitized boundary failure
     */
    public static McpBoundaryFailure create(McpBoundaryFailureKind failureKind) {
        return new McpBoundaryFailure(UUID.randomUUID().toString(), failureKind);
    }
}
