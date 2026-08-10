package com.nimbly.mcpjavadevtools.server.mcp.resource.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.lifecycle.ServerRuntimeMetadata;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class StatusMcpResourceTest {

    @Test
    void unexpectedAcceptedBoundaryFailureUsesTheSanitizedInternalErrorContract() {
        StatusMcpResource resource = new StatusMcpResource(
                new ServerRuntimeMetadata("0.1.9-test"),
                new WorkspaceContext(new DefaultApplicationArguments()));

        String response = resource.status(new FailingRootsExchange(new IllegalStateException("test failure")));

        assertThat(response).isEqualTo("{\"ok\":false,\"reasonCode\":\"internal_error\"}");
    }

    private static class FailingRootsExchange extends McpSyncServerExchange {

        private final RuntimeException failure;

        private FailingRootsExchange(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public McpSchema.ListRootsResult listRoots() {
            throw failure;
        }
    }
}
