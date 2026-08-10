package com.nimbly.mcpjavadevtools.poc;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SpringAiStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    @Test
    void executableJarPreservesMcpStdioContracts() throws Exception {
        try (ProtocolProcess server = ProtocolProcess.start(jarPath())) {
            server.send(Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "spring-ai-stdio-poc-it", "version", "1.0"))));
            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText()).isEqualTo(PROTOCOL_VERSION);

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            JsonNode tools = server.responseFor(2).path("result").path("tools");
            assertThat(toolNames(tools)).containsExactlyInAnyOrder("debug_check", "jvm_lifecycle");
            assertJvmLifecycleSchema(tools);
            assertReadOnlyToolMetadata(tools);

            server.send(request(3, "resources/list", Map.of()));
            JsonNode resources = server.responseFor(3).path("result").path("resources");
            assertThat(resourceUris(resources)).contains("mcp-java-dev-tools://status");

            server.send(request(4, "resources/read", Map.of("uri", "mcp-java-dev-tools://status")));
            JsonNode resourceContents = server.responseFor(4).path("result").path("contents");
            assertThat(resourceContents.isArray()).isTrue();
            JsonNode status = JSON.readTree(resourceContents.get(0).path("text").asText());
            assertThat(status.path("ok").asBoolean()).isTrue();
            assertThat(status.path("name").asText()).isEqualTo("mcp-java-dev-tools");

            server.send(request(5, "tools/call", Map.of(
                    "name", "debug_check",
                    "arguments", Map.of())));
            JsonNode debug = server.responseFor(5).path("result").path("structuredContent");
            assertThat(debug.path("ok").asBoolean())
                    .withFailMessage("debug_check response was: %s", server.stdoutText())
                    .isTrue();

            server.send(request(6, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "list_jvms", "input", Map.of()))));
            JsonNode jvmResult = server.responseFor(6).path("result").path("structuredContent");
            assertThat(jvmResult.path("resultType").asText()).isEqualTo("jvm_list");
            assertThat(jvmResult.path("status").asText()).isEqualTo("ok");
            assertThat(jvmResult.path("reasonCode").asText()).isEqualTo("ok");
            assertThat(jvmResult.path("jvms").isArray()).isTrue();

            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.stdoutText()).doesNotContain("Spring Boot");
            assertThat(server.stderrText()).isNotEmpty();
        }
    }

    @Test
    void stdinCloseTerminatesWithinBoundedTimeoutAndCollectsMeasurements() throws Exception {
        List<Long> startupMs = new ArrayList<>();
        List<Long> memoryBytes = new ArrayList<>();
        for (int sample = 0; sample < 3; sample++) {
            try (ProtocolProcess server = ProtocolProcess.start(jarPath())) {
                long started = System.nanoTime();
                server.send(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", PROTOCOL_VERSION,
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "spring-ai-measurement-it", "version", "1.0"))));
                assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                        .isEqualTo(PROTOCOL_VERSION);
                startupMs.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
                memoryBytes.add(server.workingSetBytes());
            }
        }
        writeMeasurements(startupMs, memoryBytes);
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Path jarPath() {
        String configured = System.getProperty("pocJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property pocJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static Set<String> toolNames(JsonNode tools) {
        Set<String> names = new java.util.HashSet<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static Set<String> resourceUris(JsonNode resources) {
        Set<String> uris = new java.util.HashSet<>();
        resources.forEach(resource -> uris.add(resource.path("uri").asText()));
        return uris;
    }

    private static void assertJvmLifecycleSchema(JsonNode tools) {
        JsonNode tool = null;
        for (JsonNode candidate : tools) {
            if (candidate.path("name").asText().equals("jvm_lifecycle")) {
                tool = candidate;
                break;
            }
        }
        assertThat(tool).isNotNull();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required").toString()).contains("action", "input");
        assertThat(schema.path("properties").path("action").path("const").asText())
                .withFailMessage("Generated jvm_lifecycle schema was: %s", schema.toPrettyString())
                .isEqualTo("list_jvms");
        assertThat(schema.path("properties").path("input").path("type").asText())
                .isEqualTo("object");
    }

    private static void assertReadOnlyToolMetadata(JsonNode tools) {
        for (JsonNode tool : tools) {
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(annotations.path("destructiveHint").asBoolean()).isFalse();
            assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
            assertThat(annotations.path("openWorldHint").asBoolean()).isFalse();
        }
    }

    private void assertJsonRpcMessage(String line) {
        try {
            JsonNode message = JSON.readTree(line);
            assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        } catch (IOException exception) {
            fail("stdout line was not valid JSON-RPC: " + line, exception);
        }
    }

    private static void writeMeasurements(List<Long> startupMs, List<Long> memoryBytes) throws IOException {
        Path output = Path.of("target", "poc-measurements.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(Map.of(
                "startupMs", startupMs,
                "memoryBytes", memoryBytes)), StandardCharsets.UTF_8);
    }

    private static final class ProtocolProcess implements AutoCloseable {

        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> stderrQueue = new LinkedBlockingQueue<>();
        private final List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);
        private volatile String stdoutError;

        private ProtocolProcess(Process process) {
            this.process = process;
            this.stdin = process.getOutputStream();
            readers.submit(() -> readStdout(process.getInputStream()));
            readers.submit(() -> readStderr(process.getErrorStream()));
        }

        static ProtocolProcess start(Path jar) throws IOException {
            Process process = new ProcessBuilder(
                    javaBinary(), "-jar", jar.toString())
                    .redirectErrorStream(false)
                    .start();
            return new ProtocolProcess(process);
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
            throw new AssertionError("Timed out waiting for JSON-RPC response id=" + id
                    + ". stdout=" + stdoutLines + ". stderr=" + stderr);
        }

        List<String> stdoutLines() {
            synchronized (stdoutLines) {
                return List.copyOf(stdoutLines);
            }
        }

        String stdoutText() {
            return String.join("\n", stdoutLines());
        }

        String stderrText() {
            drainStderr();
            return stderr.toString();
        }

        long workingSetBytes() {
            try {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    Process sample = new ProcessBuilder(
                            "powershell.exe", "-NoProfile", "-Command",
                            "(Get-Process -Id " + process.pid() + ").WorkingSet64")
                            .redirectErrorStream(true)
                            .start();
                    String value = new String(sample.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    sample.waitFor(2, TimeUnit.SECONDS);
                    return Long.parseLong(value);
                }
                Process sample = new ProcessBuilder("sh", "-c", "ps -o rss= -p " + process.pid())
                        .redirectErrorStream(true)
                        .start();
                String value = new String(sample.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                sample.waitFor(2, TimeUnit.SECONDS);
                return Long.parseLong(value) * 1024L;
            } catch (Exception exception) {
                return -1L;
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stdin.close();
                if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                    }
                    fail("MCP process did not terminate after stdin close within " + SHUTDOWN_TIMEOUT);
                }
                if (stdoutError != null) {
                    fail("stdout contained a non-JSON-RPC line: " + stdoutError);
                }
                assertThat(process.exitValue()).isZero();
            } finally {
                readers.shutdownNow();
                readers.awaitTermination(2, TimeUnit.SECONDS);
                drainStderr();
            }
        }

        private void readStdout(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        stdoutLines.add(line);
                        try {
                            JsonNode message = JSON.readTree(line);
                            if (!"2.0".equals(message.path("jsonrpc").asText())) {
                                stdoutError = "invalid jsonrpc version: " + line;
                            }
                        } catch (IOException exception) {
                            stdoutError = line + " (" + exception.getMessage() + ")";
                        }
                        stdoutQueue.offer(line);
                    }
                }
            } catch (IOException exception) {
                stdoutError = "stdout reader error: " + exception.getMessage();
            }
        }

        private void readStderr(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        stderr.append(line).append('\n');
                    }
                    stderrQueue.offer(line);
                }
            } catch (IOException exception) {
                synchronized (stderr) {
                    stderr.append("stderr reader error: ").append(exception.getMessage()).append('\n');
                }
            }
        }

        private void drainStderr() {
            String line;
            while ((line = stderrQueue.poll()) != null) {
                // stderr is already accumulated by the reader; drain only ensures queue progress.
            }
        }

        private static String javaBinary() {
            return Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                    .toString();
        }
    }
}
