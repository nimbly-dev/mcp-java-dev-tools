package com.nimbly.mcpjavadevtools.server.mcp.error;

import java.util.Objects;

/**
 * Unexpected Application Adapter failure with a closed internal classification.
 */
public class McpBoundaryException extends RuntimeException {

    private final McpBoundaryFailureKind failureKind;

    /**
     * Creates one unexpected boundary failure.
     *
     * @param failureKind closed failure classification
     * @param cause original unexpected failure, retained only for application logging
     */
    public McpBoundaryException(McpBoundaryFailureKind failureKind, Throwable cause) {
        super(Objects.requireNonNull(cause, "cause must not be null"));
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
    }

    /**
     * Returns the closed internal failure classification.
     *
     * @return safe failure classification
     */
    public McpBoundaryFailureKind failureKind() {
        return failureKind;
    }
}
