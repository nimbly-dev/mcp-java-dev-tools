package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class McpServerStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    @Test
    void executableJarStartsInStdioModeWithoutContaminatingStdout() throws Exception {
        Path workspaceRoot = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot)) {
            server.send(initializeRequest());

            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            JsonNode tools = server.responseFor(2).path("result").path("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools).extracting(node -> node.path("name").asText()).containsExactly("debug_check");

            server.send(request(3, "tools/call", Map.of("name", "debug_check", "arguments", Map.of())));
            JsonNode debugCheck = server.responseFor(3);
            JsonNode debugCheckPayload = toolPayload(debugCheck);
            JsonNode structuredDebugCheck = debugCheck.path("result").path("structuredContent");
            assertThat(debugCheckPayload.path("ok").asBoolean()).isTrue();
            assertThat(debugCheckPayload.path("version").asText()).isEqualTo("0.1.9");
            assertThat(debugCheckPayload.path("workspaceRoot").asText()).isEqualTo(workspaceRoot.toString());
            assertThat(structuredDebugCheck.isObject()).isTrue();
            assertThat(structuredDebugCheck.path("ok").asBoolean()).isTrue();
            assertThat(structuredDebugCheck.path("workspaceRoot").asText()).isEqualTo(workspaceRoot.toString());

            server.send(request(4, "resources/list", Map.of()));
            JsonNode resources = server.responseFor(4).path("result").path("resources");
            assertThat(resources).extracting(node -> node.path("uri").asText())
                    .contains("mcp-java-dev-tools://status");

            server.send(request(5, "resources/read", Map.of("uri", "mcp-java-dev-tools://status")));
            JsonNode status = server.responseFor(5);
            JsonNode statusPayload = resourcePayload(status);
            assertThat(statusPayload.path("ok").asBoolean()).isTrue();
            assertThat(statusPayload.path("workspaceRootSource").asText()).isEqualTo("roots");
            assertThat(statusPayload.path("rootsDiscoveryStatus").asText()).isEqualTo("available");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.stderrText()).isNotBlank();
        }
    }

    @Test
    void executableJarReportsStartupFailureWithoutLeakingConfiguration() throws Exception {
        Path workspaceRoot = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(), workspaceRoot, "--spring.ai.mcp.server.stdio=not-a-boolean")) {
            server.awaitStartupFailure();

            assertThat(server.stdoutLines()).isEmpty();
            assertThat(server.stderrText())
                    .contains("mcp_java_dev_tools_startup_failed reasonCode=startup_failed")
                    .doesNotContain("not-a-boolean");
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigint() throws Exception {
        assertPosixSignalStopsExecutable("INT");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigterm() throws Exception {
        assertPosixSignalStopsExecutable("TERM");
    }

    private static Map<String, Object> initializeRequest() {
        return request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcp-server-foundation-it", "version", "1.0")));
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Path jarPath() {
        String configured = System.getProperty("mcpServerJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property mcpServerJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static Path workspaceRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "mcp-java-dev-tools-stdio-it-workspace");
    }

    private void assertPosixSignalStopsExecutable(String signal) throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.sendPosixSignal(signal);
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        }
    }

    private static JsonNode toolPayload(JsonNode response) throws IOException {
        String text = response.path("result").path("content").get(0).path("text").asText();
        return JSON.readTree(text);
    }

    private static JsonNode resourcePayload(JsonNode response) throws IOException {
        String text = response.path("result").path("contents").get(0).path("text").asText();
        return JSON.readTree(text);
    }

    private void assertJsonRpcMessage(String line) {
        try {
            JsonNode message = JSON.readTree(line);
            assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        } catch (IOException exception) {
            fail("stdout line was not valid JSON-RPC: " + line, exception);
        }
    }

    private static class McpServerProcess implements AutoCloseable {

        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
        private final List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);
        private final String workspaceRootUri;
        private boolean closed;

        private McpServerProcess(Process process, Path workspaceRoot) {
            this.process = process;
            this.stdin = process.getOutputStream();
            this.workspaceRootUri = workspaceRoot.toUri().toString();
            readers.submit(() -> collectStdout(process.getInputStream()));
            readers.submit(() -> collectStderr(process.getErrorStream()));
        }

        static McpServerProcess start(Path jar, Path workspaceRoot, String... applicationArguments)
                throws IOException {
            List<String> command = new ArrayList<>(List.of(javaBinary(), "-jar", jar.toString()));
            command.addAll(List.of(applicationArguments));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            return new McpServerProcess(process, workspaceRoot);
        }

        void send(Map<String, Object> message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        void send(JsonNode message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode responseFor(int id) throws Exception {
            Instant deadline = Instant.now().plus(RESPONSE_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                String line = stdoutQueue.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                JsonNode message = JSON.readTree(line);
                if ("roots/list".equals(message.path("method").asText())) {
                    respondToRootsRequest(message);
                    continue;
                }
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for JSON-RPC response " + id + ". stderr=" + stderrText());
        }

        private void respondToRootsRequest(JsonNode request) throws IOException {
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result")
                    .putArray("roots")
                    .addObject()
                    .put("uri", workspaceRootUri)
                    .put("name", "stdio-integration-workspace");
            send(response);
        }

        List<String> stdoutLines() {
            synchronized (stdoutLines) {
                return List.copyOf(stdoutLines);
            }
        }

        String stderrText() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        void closeInputAndAwaitTermination() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            stdin.close();
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after stdin closed within " + SHUTDOWN_TIMEOUT);
            }
            awaitReaders();
            assertThat(process.exitValue()).isZero();
        }

        void awaitStartupFailure() throws Exception {
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after startup failure within " + SHUTDOWN_TIMEOUT);
            }
            closed = true;
            awaitReaders();
            assertThat(process.exitValue()).isNotZero();
        }

        void sendPosixSignal(String signal) throws Exception {
            Process signalProcess = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                    .redirectErrorStream(true)
                    .start();
            assertThat(signalProcess.waitFor(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(signalProcess.exitValue()).isZero();
            awaitSignalTermination(signal);
        }

        @Override
        public void close() throws Exception {
            try {
                closeInputAndAwaitTermination();
            } finally {
                awaitReaders();
            }
        }

        private void awaitReaders() throws InterruptedException {
            readers.shutdown();
            if (!readers.awaitTermination(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                readers.shutdownNow();
                readers.awaitTermination(2, TimeUnit.SECONDS);
                fail("MCP output readers did not drain within " + RESPONSE_TIMEOUT);
            }
        }

        private void awaitSignalTermination(String signal) throws Exception {
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after SIG" + signal + " within " + SHUTDOWN_TIMEOUT);
            }
            closed = true;
            awaitReaders();
        }

        private void collectStdout(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        stdoutLines.add(line);
                        stdoutQueue.offer(line);
                    }
                }
            } catch (IOException exception) {
                appendStderr("stdout reader error: " + exception.getMessage());
            }
        }

        private void collectStderr(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException exception) {
                appendStderr("stderr reader error: " + exception.getMessage());
            }
        }

        private void appendStderr(String line) {
            synchronized (stderr) {
                stderr.append(line).append('\n');
            }
        }

        private static String javaBinary() {
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            String executable = windows ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", executable).toString();
        }
    }
}
