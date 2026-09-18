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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Raw-STDIO compatibility evidence for the six Route Synthesis and Failure Analysis rows. */
class RouteFailureLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> ROUTES = Set.of(
            "infer_target", "class_methods", "discover_handlers", "create_recipe");
    private static final Set<String> FAILURES = Set.of(
            "analyze_trace", "verify_reproduction");

    @TempDir
    Path workspace;

    @Test
    void packagedServerPreservesAllSixLegacyOperations() throws Exception {
        writeController();
        HttpServer sidecar = startSidecar();
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(request(2, "tools/list", Map.of()));
            assertInventory(server.responseFor(2));
            exerciseRoutes(server, sidecar.getAddress().getPort());
            exerciseFailures(server, sidecar.getAddress().getPort());
        } finally {
            sidecar.stop(0);
        }
    }

    private void exerciseRoutes(ServerProcess server, int port) throws Exception {
        Map<String, Object> rootAndClass = Map.of(
                "projectRootAbs", workspace.toString(),
                "classHint", "example.StdioController");
        Map<String, Object> infer = Map.of(
                "projectRootAbs", workspace.toString(),
                "classHint", "example.StdioController",
                "methodHint", "run",
                "probeBaseUrl", "http://127.0.0.1:" + port);
        assertRoute(server.call(3, "route_synthesis", "infer_target", infer),
                "ranked_candidates");
        assertRoute(server.call(4, "route_synthesis", "class_methods", rootAndClass),
                "class_methods");
        assertRoute(server.call(5, "route_synthesis", "discover_handlers", rootAndClass),
                "handler_inventory");
        Map<String, Object> recipe = Map.of(
                "projectRootAbs", workspace.toString(),
                "classHint", "example.StdioController",
                "methodHint", "run",
                "lineHint", 7,
                "intentMode", "line_probe",
                "discoveryPreference", "static_only",
                "probeBaseUrl", "http://127.0.0.1:" + port);
        assertRoute(server.call(6, "route_synthesis", "create_recipe", recipe), "recipe");
    }

    private static void exerciseFailures(ServerProcess server, int port) throws Exception {
        String baseUrl = "http://127.0.0.1:" + port;
        Map<String, Object> analyze = Map.of(
                "trace", "java.lang.IllegalStateException: trace-secret\n"
                        + "    at example.StdioController.run(StdioController.java:7)",
                "sidecarBaseUrl", baseUrl,
                "sidecarAuthorization", "Bearer sidecar-secret",
                "timeoutMs", 15000);
        JsonNode analyzed = server.call(7, "failure_analysis", "analyze_trace", analyze);
        assertThat(analyzed.path("outcome").asText()).as(analyzed.toString()).isEqualTo("ANALYZED");
        assertThat(analyzed.path("fingerprint").path("exceptionType").asText())
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(analyzed.toString()).doesNotContain("trace-secret", "sidecar-secret");

        Map<String, Object> expected = Map.of(
                "exceptionType", "java.lang.IllegalStateException",
                "rootCauseType", "java.lang.IllegalArgumentException",
                "nearestApplicationMethodKey", "example.StdioController#run:7");
        Map<String, Object> verify = Map.of(
                "captureId", "capture-621",
                "expectedFingerprint", expected,
                "lineHit", Map.of(
                        "strictLineKey", "example.StdioController#run:7", "hitCount", 1),
                "sidecarBaseUrl", baseUrl,
                "sidecarAuthorization", "Bearer sidecar-secret",
                "timeoutMs", 15000);
        JsonNode reproduced = server.call(8, "failure_analysis", "verify_reproduction", verify);
        assertThat(reproduced.path("outcome").asText()).as(reproduced.toString())
                .isEqualTo("REPRODUCED");
        assertThat(reproduced.path("reasonCode").asText()).isEqualTo("ok");
        assertThat(reproduced.path("lineHit").path("hitCount").asInt()).isEqualTo(1);
        assertThat(reproduced.toString()).doesNotContain("sidecar-secret");
    }

    private static void assertRoute(JsonNode payload, String resultType) {
        assertThat(payload.path("resultType").asText()).as(payload.toString()).isEqualTo(resultType);
        assertThat(payload.path("status").asText()).isIn("ok", "ready", "partial");
        assertThat(payload.path("details").isObject()).isTrue();
    }

    private static void assertInventory(JsonNode response) {
        JsonNode tools = response.path("result").path("tools");
        assertActions(tool(tools, "route_synthesis"), ROUTES);
        assertActions(tool(tools, "failure_analysis"), FAILURES);
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

    private static void assertActions(JsonNode tool, Set<String> expected) {
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        Set<String> actions = new LinkedHashSet<>();
        for (JsonNode branch : schema.path("oneOf")) {
            actions.add(branch.path("properties").path("action").path("const").asText());
            assertThat(branch.path("additionalProperties").asBoolean()).isFalse();
            assertThat(branch.path("properties").path("input")
                    .path("additionalProperties").asBoolean()).isFalse();
        }
        assertThat(actions).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static HttpServer startSidecar() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/__probe/status", exchange -> respond(exchange, """
                {"probe":{"key":"example.StdioController#run:7","hitCount":1,
                "lastHitEpoch":1,"lineResolvable":true,"lineValidation":"resolvable"}}
                """));
        server.createContext("/__probe/failure/analyze", exchange -> respond(exchange, """
                {"fingerprint":{"exceptionType":"java.lang.IllegalStateException",
                "rootCauseType":"java.lang.IllegalArgumentException",
                "nearestApplicationMethodKey":"example.StdioController#run:7",
                "complete":true,"normalizedMessage":"safe failure"}}
                """));
        server.createContext("/__probe/failure/verify", exchange -> respond(exchange, """
                {"outcome":"matched","observedFingerprint":{
                "exceptionType":"java.lang.IllegalStateException",
                "rootCauseType":"java.lang.IllegalArgumentException",
                "nearestApplicationMethodKey":"example.StdioController#run:7","complete":true}}
                """));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeController() throws IOException {
        Path source = workspace.resolve("src/main/java/example/StdioController.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class StdioController {
                    @GetMapping("/stdio/run")
                    public String run() {
                        return "ok";
                    }
                }
                """);
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-621-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                "params", Map.of()));
    }

    private static Map<String, Object> request(
            int id, String method, Map<String, Object> params) {
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

        JsonNode call(int id, String tool, String action, Map<String, Object> input)
                throws Exception {
            send(request(id, "tools/call", Map.of(
                    "name", tool,
                    "arguments", Map.of("action", action, "input", input))));
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
                    .put("uri", workspaceUri).put("name", "mcpjvm-621-workspace");
            stdin.write(JSON.writeValueAsBytes(response));
            stdin.write('\n');
            stdin.flush();
        }

        private void collect(InputStream stream, boolean protocol) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    collectLine(line, protocol);
                }
            } catch (IOException exception) {
                synchronized (stderr) {
                    stderr.append(exception.getMessage()).append('\n');
                }
            }
        }

        private void collectLine(String line, boolean protocol) {
            if (protocol && !line.isBlank()) {
                stdout.add(line);
                responses.offer(line);
                return;
            }
            if (!protocol) {
                synchronized (stderr) {
                    stderr.append(line).append('\n');
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
