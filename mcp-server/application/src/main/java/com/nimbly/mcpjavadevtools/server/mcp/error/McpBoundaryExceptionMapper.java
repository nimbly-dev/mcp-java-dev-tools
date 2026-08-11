package com.nimbly.mcpjavadevtools.server.mcp.error;

/**
 * Maps unexpected Application Adapter faults to a transport-neutral failure.
 */
public class McpBoundaryExceptionMapper {

    /**
     * Produces a bounded failure that reveals neither cause type nor cause detail.
     *
     * @param exception unexpected boundary failure
     * @return sanitized boundary failure
     */
    public McpBoundaryFailure map(McpBoundaryException exception) {
        return McpBoundaryFailure.create(exception.failureKind());
    }
}
