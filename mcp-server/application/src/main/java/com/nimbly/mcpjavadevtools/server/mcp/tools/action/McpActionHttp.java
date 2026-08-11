package com.nimbly.mcpjavadevtools.server.mcp.tools.action;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Optional HTTP header input shared by action-based MCP Tools.
 *
 * @param headers optional endpoint request headers
 */
public record McpActionHttp(@Nullable Map<String, String> headers) {
}
