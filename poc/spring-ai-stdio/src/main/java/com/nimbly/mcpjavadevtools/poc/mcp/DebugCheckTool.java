package com.nimbly.mcpjavadevtools.poc.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.ai.mcp.annotation.McpTool;

@Component
public final class DebugCheckTool {

    @McpTool(
            name = "debug_check",
            description = "Sanity check: confirms the MCP server is reachable.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public io.modelcontextprotocol.spec.McpSchema.CallToolResult debugCheck() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("serverTime", Instant.now().toString());
        output.put("version", "0.1.0-poc");
        output.put("pid", ProcessHandle.current().pid());
        return McpToolResponse.structured(output);
    }
}
