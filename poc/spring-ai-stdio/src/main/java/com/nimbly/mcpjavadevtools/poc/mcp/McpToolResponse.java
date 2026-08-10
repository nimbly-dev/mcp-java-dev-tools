package com.nimbly.mcpjavadevtools.poc.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;

public final class McpToolResponse {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpToolResponse() {
    }

    public static CallToolResult structured(Map<String, Object> content) {
        try {
            return CallToolResult.builder()
                    .addTextContent(JSON.writeValueAsString(content))
                    .structuredContent(content)
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize deterministic MCP output", exception);
        }
    }

    public static String json(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize deterministic MCP Resource", exception);
        }
    }
}
