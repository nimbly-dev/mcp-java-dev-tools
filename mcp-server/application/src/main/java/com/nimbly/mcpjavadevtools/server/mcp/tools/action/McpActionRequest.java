package com.nimbly.mcpjavadevtools.server.mcp.tools.action;

/**
 * Action-discriminated MCP input shared by consolidated action-based Tools.
 *
 * @param action required consolidated action discriminator
 * @param input capability-owned action input fields
 * @param <I> capability-specific input type
 */
public record McpActionRequest<I>(String action, I input) {
}
