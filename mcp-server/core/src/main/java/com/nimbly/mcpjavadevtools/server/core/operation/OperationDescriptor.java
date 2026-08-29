package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Objects;

/**
 * Immutable identity and trace metadata for one executable MCP operation.
 *
 * <p>The descriptor contains metadata only. It never stores executable
 * lambdas, I/O, Spring types, or capability behavior.</p>
 */
public record OperationDescriptor(
        String toolName,
        String action,
        String requestType,
        String resultType,
        String executableOwner,
        OperationTraceMetadata trace) {

    /** Validates the complete minimum descriptor contract. */
    public OperationDescriptor {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");
        Objects.requireNonNull(executableOwner, "executableOwner must not be null");
        Objects.requireNonNull(trace, "trace must not be null");
        if (toolName.isBlank() || action.isBlank() || requestType.isBlank()
                || resultType.isBlank() || executableOwner.isBlank()) {
            throw new IllegalArgumentException("operation descriptor values must not be blank");
        }
    }
}
