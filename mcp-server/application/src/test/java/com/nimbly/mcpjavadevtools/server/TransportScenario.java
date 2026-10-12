package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport-owned assertions over the existing packaged MCP driver and scenario lifecycle. */
class TransportScenario implements AutoCloseable {
    static final String OPERATION = "transport_execute.execute";
    final Path workspace;
    final McpServerProcess server;
    private final List<String> secrets;
    private final AtomicBoolean stderrContainsSecret;
    private int nextId = 100;

    private TransportScenario(Path workspace, McpServerProcess server, List<String> secrets, AtomicBoolean stderrContainsSecret) {
        this.workspace = workspace;
        this.server = server;
        this.secrets = secrets;
        this.stderrContainsSecret = stderrContainsSecret;
    }

    static TransportScenario start(String... secrets) throws Exception {
        return startWithSecrets(JavaItScenario.workspace(), Map.of(), List.of(secrets));
    }

    static TransportScenario start(Path workspace) throws Exception {
        return start(workspace, Map.of());
    }

    static TransportScenario start(Path workspace, Map<String, String> environment, String... arguments) throws Exception {
        return startWithSecrets(workspace, environment, List.of(), arguments);
    }

    static TransportScenario startWithSecrets(Path workspace, Map<String, String> environment,
            List<String> secrets, String... arguments) throws Exception {
        List<String> sentinels = List.copyOf(secrets);
        AtomicBoolean stderrContainsSecret = new AtomicBoolean();
        McpServerProcess server = McpServerProcess.startWithStderrObserver(McpServerStdioSupport.jarPath(), workspace,
                environment, line -> {
                    if (sentinels.stream().anyMatch(line::contains)) {
                        stderrContainsSecret.set(true);
                    }
                }, arguments);
        try {
            McpServerStdioSupport.initialize(server);
            return new TransportScenario(workspace, server, sentinels, stderrContainsSecret);
        } catch (Throwable failure) {
            server.close();
            throw failure;
        }
    }

    JsonNode call(String operation, Map<String, Object> arguments, boolean confirmed) throws Exception {
        int id = nextId++;
        server.send(McpServerStdioSupport.executeRequest(id, operation, arguments, confirmed));
        return McpServerStdioSupport.toolPayload(server.responseFor(id));
    }

    JsonNode execute(Map<String, Object> request) throws Exception {
        return result(Map.of("protocol", "http", "request", request));
    }

    JsonNode result(Map<String, Object> arguments) throws Exception {
        JsonNode payload = call(OPERATION, arguments, true);
        assertThat(payload.path("status").asText()).isEqualTo("succeeded");
        JsonNode result = payload.path("result");
        assertThat(result.path("durationMs").asLong()).isPositive();
        JavaItScenario.observe(OPERATION, result.path("status").asText(), result.path("reasonCode").asText());
        return result;
    }

    static void assertBlocked(JsonNode result, String status, String reason) {
        assertThat(result.path("status").asText()).isEqualTo(status);
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(result.path("nextActionCode").asText()).isEqualTo(reason);
        assertThat(result.path("statusCode").isNull() || result.path("statusCode").isMissingNode()).isTrue();
        assertThat(result.path("reasonMeta").path("failedStep").asText()).isNotBlank();
    }

    void assertSecretsAbsent(String... secrets) throws Exception {
        assertThat(this.secrets.containsAll(List.of(secrets)))
                .as("secret sentinels must be registered before starting the server").isTrue();
        assertSanitized(String.join("\n", server.stdoutLines()), secrets);
        assertRawStderrSanitized();
        try (var paths = Files.walk(workspace)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                assertSanitized(Files.readString(file), secrets);
            }
        }
    }

    private void assertRawStderrSanitized() {
        assertThat(stderrContainsSecret.get()).as("raw server stderr must not contain scenario credentials").isFalse();
    }

    static void assertSanitized(String text, String... secrets) {
        for (String secret : secrets) {
            assertThat(text.contains(secret)).as("credentials must be absent from returned/captured/generated data").isFalse();
        }
    }

    @Override
    public void close() throws Exception {
        try {
            server.closeInputAndAwaitTermination();
            assertRawStderrSanitized();
            assertThat(server.stdoutLines()).allSatisfy(line -> {
                try {
                    assertThat(McpServerStdioSupport.JSON.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
                } catch (java.io.IOException failure) {
                    throw new AssertionError("Non-JSON stdout", failure);
                }
            });
        } finally {
            server.close();
        }
    }
}
