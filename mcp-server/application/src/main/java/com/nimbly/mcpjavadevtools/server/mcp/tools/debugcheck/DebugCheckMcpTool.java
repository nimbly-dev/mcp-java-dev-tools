package com.nimbly.mcpjavadevtools.server.mcp.tools.debugcheck;

import com.nimbly.mcpjavadevtools.server.lifecycle.ServerRuntimeMetadata;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DebugCheckMcpTool {

    private final ServerRuntimeMetadata runtimeMetadata;
    private final WorkspaceContext workspaceContext;

    @McpTool(
            name = "debug_check",
            description = "Sanity check: confirms the MCP server is reachable.",
            generateOutputSchema = true)
    public DebugCheckResponse debugCheck(McpSyncRequestContext requestContext) {
        workspaceContext.refreshFrom(requestContext);
        return response();
    }

    DebugCheckResponse response() {
        WorkspaceSnapshot workspace = workspaceContext.snapshot();
        return new DebugCheckResponse(
                true,
                runtimeMetadata.timestamp(),
                runtimeMetadata.version(),
                runtimeMetadata.buildFingerprint(),
                runtimeMetadata.processId(),
                runtimeMetadata.parentProcessId(),
                workspace.rootText());
    }
}
