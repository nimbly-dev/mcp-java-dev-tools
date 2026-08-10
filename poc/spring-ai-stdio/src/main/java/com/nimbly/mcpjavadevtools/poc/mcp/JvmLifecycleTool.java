package com.nimbly.mcpjavadevtools.poc.mcp;

import com.nimbly.mcpjavadevtools.poc.core.jvm.JvmLifecycleListAction;
import com.nimbly.mcpjavadevtools.poc.core.jvm.JvmLifecycleResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public final class JvmLifecycleTool {

    private final JvmLifecycleListAction listAction;

    public JvmLifecycleTool() {
        this.listAction = new JvmLifecycleListAction();
    }

    @McpTool(
            name = "jvm_lifecycle",
            description = "Discover local JVMs and safely attach or deactivate the repository-owned Sidecar Agent.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult jvmLifecycle(
            @McpToolParam(description = "Requested lifecycle action. The POC supports list_jvms.", required = true)
            JvmLifecycleAction action,
            @McpToolParam(description = "Action input. list_jvms accepts an empty object.", required = true)
            ListJvmsInput input) {
        if (action != JvmLifecycleAction.list_jvms || input == null) {
            return McpToolResponse.structured(Map.of(
                    "resultType", "report",
                    "status", "blocked",
                    "reasonCode", "jvm_lifecycle_request_invalid",
                    "jvms", java.util.List.of()));
        }
        JvmLifecycleResult result = listAction.execute();
        return McpToolResponse.structured(result.structuredContent());
    }

    public enum JvmLifecycleAction {
        list_jvms
    }

    public record ListJvmsInput() {
    }
}
