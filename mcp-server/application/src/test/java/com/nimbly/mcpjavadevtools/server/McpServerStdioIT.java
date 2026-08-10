package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class McpServerStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    @Test
    void executableJarStartsInStdioModeWithoutContaminatingStdout() throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath())) {
            server.send(initializeRequest());

            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            assertThat(server.responseFor(2).path("result").path("tools").isArray()).isTrue();

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.stderrText()).isNotBlank();
        }
    }

    private static Map<String, Object> initializeRequest() {
        return request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
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

    private void assertJsonRpcMessage(String line) {
        try {
            JsonNode message = JSON.readTree(line);
            assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        } catch (IOException exception) {
            fail("stdout line was not valid JSON-RPC: " + line, exception);
        }
    }

    private static final class McpServerProcess implements AutoCloseable {

        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
        private final List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);
        private boolean closed;

        private McpServerProcess(Process process) {
            this.process = process;
            this.stdin = process.getOutputStream();
            readers.submit(() -> collectStdout(process.getInputStream()));
            readers.submit(() -> collectStderr(process.getErrorStream()));
        }

        static McpServerProcess start(Path jar) throws IOException {
            Process process = new ProcessBuilder(javaBinary(), "-jar", jar.toString())
                    .redirectErrorStream(false)
                    .start();
            return new McpServerProcess(process);
        }

        void send(Map<String, Object> message) throws IOException {
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
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for JSON-RPC response " + id + ". stderr=" + stderrText());
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
            assertThat(process.exitValue()).isZero();
        }

        @Override
        public void close() throws Exception {
            try {
                closeInputAndAwaitTermination();
            } finally {
                readers.shutdownNow();
                readers.awaitTermination(2, TimeUnit.SECONDS);
            }
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
