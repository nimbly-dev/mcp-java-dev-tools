package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpSensitiveDataRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportSafetyPolicy;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("transport")
@Timeout(90)
class McpServerTransportIT extends McpServerStdioSupport {

    @Test
    void executableJarCancelsAnInFlightTransportOperation() throws Exception {
        Path workspace = JavaItScenario.workspace();
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
            assertRealRecovery(server, 40);

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
        Path workspace = JavaItScenario.workspace();
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
            assertThat(timeout.path("result").path("durationMs").asLong()).isLessThan(5_000);
            target.awaitClientDisconnect();
            assertRealRecovery(server, 41);

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void stdinClosureInterruptsAndCleansUpTheActualInFlightExchange() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (DelayedHttpTarget target = DelayedHttpTarget.start();
                McpServerProcess server = McpServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(executeRequest(35, TransportScenario.OPERATION, Map.of("protocol", "http", "request",
                    Map.of("method", "GET", "url", target.url(), "timeoutMs", 60_000)), true));
            target.awaitRequest();
            target.assertStillConnected();
            server.closeInputAndAwaitTermination();
            target.awaitClientDisconnect();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "stdin_interrupt_disconnected");
        }
    }

    private void assertRealRecovery(McpServerProcess server, int id) throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            server.send(executeRequest(id, TransportScenario.OPERATION, Map.of("protocol", "http", "request",
                    Map.of("method", "GET", "url", target.url("/recovery"))), true));
            JsonNode result = toolPayload(server.responseFor(id)).path("result");
            assertThat(result.path("status").asText()).isEqualTo("pass");
            assertThat(result.path("statusCode").asInt()).isEqualTo(200);
            assertThat(target.received().uri()).isEqualTo("/recovery");
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "actual_http_recovery");
        }
    }

    @Test
    void cancellingOneRequestLeavesAnotherInFlightRequestConnected() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (DelayedHttpTarget first = DelayedHttpTarget.start(); DelayedHttpTarget second = DelayedHttpTarget.start();
                McpServerProcess server = McpServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(executeRequest(50, TransportScenario.OPERATION, Map.of("protocol", "http", "request",
                    Map.of("method", "GET", "url", first.url(), "timeoutMs", 60_000)), true));
            first.awaitRequest();
            server.send(executeRequest(51, TransportScenario.OPERATION, Map.of("protocol", "http", "request",
                    Map.of("method", "GET", "url", second.url(), "timeoutMs", 60_000)), true));
            second.awaitRequest();
            cancel(server, 999);
            first.assertStillConnected();
            second.assertStillConnected();
            cancel(server, 50);
            first.awaitClientDisconnect();
            second.assertStillConnected();
            cancel(server, 51);
            second.awaitClientDisconnect();
            assertRealRecovery(server, 52);
            server.closeInputAndAwaitTermination();
            assertThat(server.hasResponseFor(50)).isFalse();
            assertThat(server.hasResponseFor(51)).isFalse();
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "cancellation_request_isolation");
        }
    }

    private void cancel(McpServerProcess server, int id) throws Exception {
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", id)));
    }

    @Test
    void realProviderInterruptionPreservesTheFlagAndDisconnectsTheStream() throws Exception {
        try (DelayedHttpTarget target = DelayedHttpTarget.start();
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpTransportSafetyPolicy policy = new HttpTransportSafetyPolicy(Set.of());
            HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();
            HttpTransportProvider provider = new HttpTransportProvider(new HttpRequestValidator(policy, redactor, JSON),
                    new HttpRedirectResponseExecutor(client, policy, redactor));
            AtomicReference<ExecuteTransportResult> result = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread worker = Thread.ofPlatform().start(() -> {
                result.set(provider.execute(new ExecuteTransportRequest(TransportProtocol.HTTP,
                        Map.of("method", "GET", "url", target.url(), "timeoutMs", 60_000), true)));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                target.awaitRequest();
                target.assertStillConnected();
                worker.interrupt();
                worker.join(5_000);
                assertThat(worker.isAlive()).isFalse();
                assertThat(result.get()).isNotNull();
                assertThat(result.get().status()).isEqualTo("blocked_runtime");
                assertThat(result.get().reasonCode()).isEqualTo("transport_request_cancelled");
                assertThat(interrupted.get()).isTrue();
                target.awaitClientDisconnect();
                JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "real_provider_interrupted_flag_and_disconnect");
            } finally {
                worker.interrupt();
                client.shutdownNow();
                worker.join(5_000);
                assertThat(worker.isAlive()).as("interrupted provider worker cleanup").isFalse();
            }
        }
    }

}
