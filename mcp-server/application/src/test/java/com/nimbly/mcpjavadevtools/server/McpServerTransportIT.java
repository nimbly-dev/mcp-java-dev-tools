package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerTransportIT extends McpServerStdioSupport {

    @Test
    void executableJarCancelsAnInFlightTransportOperation() throws Exception {
        Path workspace = Files.createTempDirectory(testClassesPath(), "mcpjvm-644-cancellation-");
        try (DelayedHttpTarget target = DelayedHttpTarget.start();
                McpServerProcess server = McpServerProcess.start(
                        jarPath(), workspace, "--workspace-root=" + workspace)) {
            initialize(server);
            server.send(executeRequest(30, "transport_execute.execute", Map.of(
                    "protocol", "http",
                    "request", Map.of(
                            "method", "GET",
                            "url", target.url(),
                            "timeoutMs", 60_000)), true));
            target.awaitRequest();
            target.assertStillConnected();
            server.send(Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/cancelled",
                    "params", Map.of(
                            "requestId", 30,
                            "reason", "mcpjvm-644 packaged cancellation acceptance")));

            target.awaitClientDisconnect();

            server.send(request(31, "tools/list", Map.of()));
            assertThat(server.responseFor(31).path("result").path("tools"))
                    .extracting(tool -> tool.path("name").asText())
                    .containsExactlyInAnyOrder(
                            "operation_catalog", "operation_describe", "operation_execute");
            assertThat(server.hasResponseFor(30))
                    .as("MCP cancellation must suppress the cancelled request response")
                    .isFalse();
            assertThat(server.stderrText()).doesNotContain(
                    "No handler registered for notification method: notifications/cancelled");
            server.closeInputAndAwaitTermination();
            assertThat(server.hasResponseFor(30))
                    .as("the fully drained stdout stream must not contain a late cancelled response")
                    .isFalse();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void executableJarEnforcesTransportTimeout() throws Exception {
        Path workspace = Files.createTempDirectory(testClassesPath(), "mcpjvm-644-timeout-");
        try (DelayedHttpTarget target = DelayedHttpTarget.start();
                McpServerProcess server = McpServerProcess.start(
                        jarPath(), workspace, "--workspace-root=" + workspace)) {
            initialize(server);
            server.send(executeRequest(32, "transport_execute.execute", Map.of(
                    "protocol", "http",
                    "request", Map.of(
                            "method", "GET",
                            "url", target.url(),
                            "timeoutMs", 100)), true));
            target.awaitRequest();

            JsonNode timeout = toolPayload(server.responseFor(32));
            assertThat(timeout.path("status").asText()).isEqualTo("succeeded");
            assertThat(timeout.path("result").path("status").asText()).isEqualTo("blocked_runtime");
            assertThat(timeout.path("result").path("reasonCode").asText())
                    .isEqualTo("transport_request_timeout");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        } finally {
            deleteRecursively(workspace);
        }
    }

}
