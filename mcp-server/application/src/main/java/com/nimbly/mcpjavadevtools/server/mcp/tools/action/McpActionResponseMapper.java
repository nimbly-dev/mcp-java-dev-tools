package com.nimbly.mcpjavadevtools.server.mcp.tools.action;

import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;

/**
 * Maps capability-owned Core outcomes into the shared action response envelope.
 *
 * @param <I> capability-specific Core request type
 * @param <R> capability-specific Core result type
 */
public interface McpActionResponseMapper<I, R> {

    /**
     * Maps one typed Core result.
     *
     * @param request typed Core request
     * @param result typed Core result
     * @return shared action response
     */
    McpActionResponse map(I request, R result);

    /**
     * Creates the capability's canonical invalid-request response.
     *
     * @return invalid-request response
     */
    McpActionResponse invalidRequest();

    /**
     * Maps a neutral Application boundary failure to the shared response envelope.
     *
     * @param failure sanitized boundary failure
     * @return internal-error response
     */
    McpActionResponse mapBoundary(McpBoundaryFailure failure);
}
