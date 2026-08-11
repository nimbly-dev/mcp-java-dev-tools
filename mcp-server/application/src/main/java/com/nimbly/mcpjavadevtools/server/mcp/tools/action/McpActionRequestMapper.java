package com.nimbly.mcpjavadevtools.server.mcp.tools.action;

/**
 * Converts one capability-owned action input envelope into a Core request.
 *
 * @param <I> capability-specific transport input type
 * @param <R> capability-specific Core request type
 */
@FunctionalInterface
public interface McpActionRequestMapper<I, R> {

    /**
     * Maps one transport request into its typed Core request.
     *
     * @param request action envelope
     * @return typed Core request
     */
    R map(McpActionRequest<I> request);
}
