package com.nimbly.mcpjavadevtools.server.mcp.resource.status;

import com.nimbly.mcpjavadevtools.server.lifecycle.ServerRuntimeMetadata;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class StatusMcpResource {

    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();
    private static final Logger LOGGER = LoggerFactory.getLogger(StatusMcpResource.class);
    private static final String INTERNAL_ERROR_RESPONSE = "{\"ok\":false,\"reasonCode\":\"internal_error\"}";

    private final ServerRuntimeMetadata runtimeMetadata;
    private final WorkspaceContext workspaceContext;

    public StatusMcpResource(
            ServerRuntimeMetadata runtimeMetadata,
            WorkspaceContext workspaceContext) {
        this.runtimeMetadata = runtimeMetadata;
        this.workspaceContext = workspaceContext;
    }

    @McpResource(
            name = "status",
            uri = "mcp-java-dev-tools://status",
            description = "Server status and defaults",
            mimeType = "application/json")
    public String status(McpSyncServerExchange exchange) {
        try {
            workspaceContext.refreshFrom(exchange);
            return JSON_MAPPER.writeValueAsString(response());
        } catch (Exception exception) {
            LOGGER.error("mcp_java_dev_tools_status_failed reasonCode=internal_error");
            return INTERNAL_ERROR_RESPONSE;
        }
    }

    private StatusResponse response() {
        WorkspaceSnapshot workspace = workspaceContext.snapshot();
        return new StatusResponse(
                true,
                "mcp-java-dev-tools",
                runtimeMetadata.version(),
                runtimeMetadata.buildFingerprint(),
                runtimeMetadata.processId(),
                runtimeMetadata.parentProcessId(),
                workspace.rootText(),
                workspace.sourceText(),
                workspace.reasonCode(),
                workspace.rootDiscoveryStatus(),
                new ProbeRoutingStatus("not_migrated", "probe_routing_not_migrated"),
                new RegistryStatus("not_migrated", "probe_registry_not_migrated"),
                new AuthenticationStatus("disabled"),
                runtimeMetadata.timestamp());
    }

    private record StatusResponse(
            boolean ok,
            String name,
            String version,
            String buildFingerprint,
            long pid,
            Long ppid,
            String workspaceRoot,
            String workspaceRootSource,
            String workspaceReasonCode,
            String rootsDiscoveryStatus,
            ProbeRoutingStatus probe,
            RegistryStatus registry,
            AuthenticationStatus auth,
            String time) {
    }

    private record ProbeRoutingStatus(String status, String reasonCode) {
    }

    private record RegistryStatus(String status, String reasonCode) {
    }

    private record AuthenticationStatus(String credentialDiscovery) {
    }
}
