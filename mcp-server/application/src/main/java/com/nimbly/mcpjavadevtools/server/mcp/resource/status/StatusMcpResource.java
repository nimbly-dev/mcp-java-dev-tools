package com.nimbly.mcpjavadevtools.server.mcp.resource.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Instant;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Allowlisted runtime metadata; never serializes configuration or environment. */
@Component
public class StatusMcpResource {

    private final WorkspaceContext workspace;
    private final ObjectMapper mapper;
    private final String version;

    public StatusMcpResource(WorkspaceContext workspace, ObjectMapper mapper,
            @Value("${spring.ai.mcp.server.version}") String version) {
        this.workspace = workspace;
        this.mapper = mapper;
        this.version = version;
    }

    @McpResource(name = "status", uri = "mcp-java-dev-tools://status",
            description = "Safe server runtime and workspace status", mimeType = "application/json")
    public String status(McpSyncServerExchange exchange) {
        try {
            workspace.refreshFrom(exchange);
            WorkspaceSnapshot snapshot = workspace.snapshot();
            ProcessHandle process = ProcessHandle.current();
            return mapper.writeValueAsString(new StatusResponse(
                    true, "mcp-java-dev-tools", version, "implementation-version:" + version,
                    process.pid(), process.parent().map(ProcessHandle::pid).orElse(null),
                    snapshot.rootText(), snapshot.sourceText(), snapshot.reasonCode(),
                    snapshot.rootDiscoveryStatus(), System.getProperty("java.version"), Instant.now().toString()));
        } catch (JsonProcessingException | RuntimeException failure) {
            return "{\"ok\":false,\"reasonCode\":\"internal_error\"}";
        }
    }

    private record StatusResponse(boolean ok, String name, String version, String buildFingerprint,
            long pid, Long ppid, String workspaceRoot, String workspaceRootSource,
            String workspaceReasonCode, String rootsDiscoveryStatus, String javaVersion, String time) {
    }
}
