package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Public MCP carrier retained exactly as protocol, request, and options. */
public record TransportExecuteMcpInput(
        String protocol,
        Map<String, Object> request,
        @Nullable TransportExecuteMcpOptions options) {
}
