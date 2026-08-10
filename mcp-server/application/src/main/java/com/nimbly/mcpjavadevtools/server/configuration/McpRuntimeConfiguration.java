package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpRuntimeConfiguration {

    @Bean
    BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> workspaceRootsChangeHandler(
            WorkspaceContext workspaceContext) {
        return (exchange, roots) -> workspaceContext.refreshRoots(roots);
    }
}
