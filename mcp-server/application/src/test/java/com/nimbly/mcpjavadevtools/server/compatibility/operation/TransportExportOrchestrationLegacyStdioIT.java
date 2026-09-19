package com.nimbly.mcpjavadevtools.server.compatibility.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.junit.jupiter.api.io.TempDir;

/** Raw-STDIO compatibility evidence for transport, export, and orchestration rows. */
class TransportExportOrchestrationLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    @TempDir
    Path workspace;

    @Test
    void packagedServerPreservesAllThreeReleasedInvocations() throws Exception {
        HttpServer target = startTarget();
        try {
            writeFixtures(target.getAddress().getPort());
            try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
                initialize(server);
                assertInventory(server);
                assertTransport(server, target.getAddress().getPort());
                assertExport(server);
                assertOrchestration(server);
            }
        } finally {
            target.stop(0);
        }
    }

    private static void assertInventory(ServerProcess server) throws Exception {
        server.send(request(2, "tools/list", Map.of()));
        JsonNode tools = server.responseFor(2).path("result").path("tools");
        JsonNode transport = tool(tools, "transport_execute");
        JsonNode export = tool(tools, "execution_profile_export");
        JsonNode orchestration = tool(tools, "execution_orchestration");

        assertThat(transport.path("inputSchema").path("properties").has("action")).isFalse();
        assertThat(export.path("inputSchema").path("properties").has("action")).isFalse();
        assertThat(orchestration.path("inputSchema").path("properties")
                .path("action").path("const").asText()).isEqualTo("execute");
    }

    private static void assertTransport(ServerProcess server, int port) throws Exception {
        JsonNode result = server.call(3, "transport_execute", Map.of(
                "protocol", "http",
                "request", Map.of(
                        "method", "GET",
                        "url", "http://127.0.0.1:" + port + "/transport",
                        "headers", Map.of("Authorization", "Bearer stdio-secret")),
                "options", Map.of("wrappedOnly", false)));

        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("pass");
        assertThat(result.path("statusCode").asInt()).isEqualTo(200);
        assertThat(result.path("bodyPreview").asText()).contains("transport-ok");
        assertThat(result.toString()).doesNotContain("stdio-secret", "upstream-secret");
    }

    private static void assertExport(ServerProcess server) throws Exception {
        JsonNode result = server.call(4, "execution_profile_export", Map.of(
                "projectName", "demo",
                "executionProfile", "regression-smoke",
                "mode", "sh",
                "type", "sh",
                "exportId", "stdio-export",
                "includeResolvedSecrets", false));

        assertThat(result.path("resultType").asText()).isEqualTo("execution_profile_export");
        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("exportId").asText()).isEqualTo("stdio-export");
        Path script = Path.of(result.path("output").path("scriptPathAbs").asText());
        assertThat(script).isRegularFile();
        assertThat(Files.readString(script)).contains("/transport");
    }

    private static void assertOrchestration(ServerProcess server) throws Exception {
        JsonNode result = server.call(5, "execution_orchestration", Map.of(
                "action", "execute",
                "input", Map.of(
                        "projectName", "demo",
                        "executionProfile", "regression-smoke",
                        "suiteRunId", "stdio-run",
                        "maxPlansPerCall", 1)));

        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("pass");
        assertThat(result.path("reasonCode").asText()).isEqualTo("ok");
        assertThat(result.path("suiteRunId").asText()).isEqualTo("stdio-run");
        assertThat(result.path("planRuns").toString()).contains("regression-smoke");
    }

    private void writeFixtures(int port) throws Exception {
        Path projectRoot = workspace.resolve(".mcpjvm/demo");
        Path planRoot = projectRoot.resolve("plans/regression/regression-smoke");
        Files.createDirectories(planRoot);
        ObjectNode project = JSON.createObjectNode();
        ObjectNode selectedWorkspace = project.putArray("workspaces").addObject();
        selectedWorkspace.put("projectRoot", workspace.toString());
        selectedWorkspace.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1)
                .put("resumePollIntervalMs", 10)
                .put("resumePollTimeoutMs", 100);
        selectedWorkspace.putArray("executionProfiles").addObject()
                .put("executionProfile", "regression-smoke")
                .put("executionPolicy", "stop_on_fail")
                .put("suiteType", "regression")
                .putArray("plans").addObject().put("order", 1).put("planName", "regression-smoke");
        Files.writeString(projectRoot.resolve("projects.json"), project.toPrettyString());
        Files.writeString(planRoot.resolve("metadata.json"),
                "{\"suiteType\":\"regression\",\"execution\":{\"intent\":\"regression\"}}");
        Files.writeString(planRoot.resolve("contract.json"), """
                {"targets":[{}],"steps":[{"order":1,"id":"transport","protocol":"http",
                "transport":{"http":{"method":"GET","url":"http://127.0.0.1:%d/transport"}},
                "expect":[{"id":"status","actualPath":"response.status",
                "operator":"field_equals","expected":200}]}]}
                """.formatted(port));
        Path registry = workspace.resolve(".mcpjvm");
        Files.writeString(registry.resolve("probe-config.json"), """
                {"defaultProfile":"stdio","profiles":{"stdio":{
                "global":{"allowNonWrappedExecutable":false},
                "probes":{"stdio":{"baseUrl":"http://127.0.0.1:%d"}}}}}
                """.formatted(port));
    }

    private static HttpServer startTarget() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transport", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "token=upstream-secret");
            respond(exchange, 200, "{\"message\":\"transport-ok\",\"token\":\"upstream-secret\"}");
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-622-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed", "params", Map.of()));
    }

    private static JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        fail("Missing tool " + name);
        return JSON.nullNode();
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

    private static final class ServerProcess implements AutoCloseable {
        private final Process process;
        private final OutputStream stdin;
        private final String workspaceUri;
        private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private final List<String> stdout = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);

        private ServerProcess(Process process, Path workspace) {
            this.process = process;
            stdin = process.getOutputStream();
            workspaceUri = workspace.toUri().toString();
            readers.submit(() -> collect(process.getInputStream(), true));
            readers.submit(() -> collect(process.getErrorStream(), false));
        }

        static ServerProcess start(Path jar, Path workspace) throws IOException {
            String executable = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-jar", jar.toString(), "--workspace-root=" + workspace)
                    .directory(workspace.toFile()).start();
            return new ServerProcess(process, workspace);
        }

        JsonNode call(int id, String tool, Map<String, Object> arguments) throws Exception {
            send(request(id, "tools/call", Map.of("name", tool, "arguments", arguments)));
            JsonNode response = responseFor(id);
            assertThat(response.path("result").path("isError").asBoolean())
                    .as(response.toString()).isFalse();
            String text = response.path("result").path("content").get(0).path("text").asText();
            return JSON.readTree(text);
        }

        void send(Map<String, Object> message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode responseFor(int id) throws Exception {
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                String line = responses.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                JsonNode message = JSON.readTree(line);
                if ("roots/list".equals(message.path("method").asText())) {
                    respondToRoots(message);
                } else if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for response " + id + ". stderr=" + stderr);
        }

        private void respondToRoots(JsonNode request) throws IOException {
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result").putArray("roots").addObject()
                    .put("uri", workspaceUri).put("name", "mcpjvm-622-workspace");
            stdin.write(JSON.writeValueAsBytes(response));
            stdin.write('\n');
            stdin.flush();
        }

        private void collect(InputStream stream, boolean protocol) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (protocol && !line.isBlank()) {
                        stdout.add(line);
                        responses.offer(line);
                    } else if (!protocol) {
                        synchronized (stderr) {
                            stderr.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException exception) {
                synchronized (stderr) {
                    stderr.append(exception.getMessage()).append('\n');
                }
            }
        }

        @Override
        public void close() throws Exception {
            stdin.close();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                fail("MCP process did not stop after stdin closed");
            }
            readers.shutdown();
            assertThat(readers.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(process.exitValue()).as(stderr.toString()).isZero();
            assertThat(stdout).isNotEmpty().allSatisfy(line -> {
                try {
                    assertThat(JSON.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
                } catch (IOException exception) {
                    throw new AssertionError("stdout contained a non-JSON-RPC line", exception);
                }
            });
        }
    }
}
