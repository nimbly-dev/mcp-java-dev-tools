package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact public input schema for the transport_execute MCP Tool. */
public final class TransportExecuteMcpSchema {

    private TransportExecuteMcpSchema() {
    }

    /** @return the public protocol/request/options schema without the internal action field */
    public static Map<String, Object> publicInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("protocol", Map.of(
                "type", "string",
                "enum", List.of("http", "grpc", "kafka", "custom")));
        properties.put("request", Map.of(
                "type", "object",
                "additionalProperties", true));
        properties.put("options", Map.of(
                "type", "object",
                "properties", Map.of(
                        "wrappedOnly", Map.of("type", "boolean")),
                "additionalProperties", false));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("protocol", "request"),
                "additionalProperties", false);
    }
}
