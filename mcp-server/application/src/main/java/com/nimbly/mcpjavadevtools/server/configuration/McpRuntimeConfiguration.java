package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.mcp.resource.status.StatusMcpResource;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import com.nimbly.mcpjavadevtools.server.mcp.transport.CancellationAwareStdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class McpRuntimeConfiguration {

    @Bean
    List<McpServerFeatures.SyncResourceSpecification> statusResourceSpecifications(StatusMcpResource status) {
        return SyncMcpAnnotationProviders.resourceSpecifications(List.of(status));
    }

    @Bean
    McpServerTransportProviderBase cancellationAwareStdioServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
        return new CancellationAwareStdioServerTransportProvider(new JacksonMcpJsonMapper(jsonMapper));
    }

    @Bean
    BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> workspaceRootsChangeHandler(
            WorkspaceContext workspaceContext) {
        return (exchange, roots) -> workspaceContext.refreshRoots(roots);
    }
}
