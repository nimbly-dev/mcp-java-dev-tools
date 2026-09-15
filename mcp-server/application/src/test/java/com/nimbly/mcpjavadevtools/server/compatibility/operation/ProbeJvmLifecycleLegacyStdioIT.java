package com.nimbly.mcpjavadevtools.server.compatibility.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Packaged Java raw-STDIO non-regression evidence for MCPJVM-620's ten rows. */
class ProbeJvmLifecycleLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> PROBE_ACTIONS = Set.of(
            "actuate", "capture", "check", "profiler", "reset", "status", "wait_for_hit");
    private static final Set<String> JVM_ACTIONS = Set.of("attach", "deactivate", "list_jvms");

    @TempDir
    Path workspace;

    @Test
    void packagedServerExercisesAllTenReleasedRows() throws Exception {
        HttpServer sidecar = sidecar();
        sidecar.start();
        String baseUrl = "http://127.0.0.1:" + sidecar.getAddress().getPort();
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(request(2, "tools/list", Map.of()));
            assertInventory(server.responseFor(2));

            int id = 3;
            assertProbe(server.call(id++, "probe", "check", target(baseUrl)), "check");
            assertProbe(server.call(id++, "probe", "status", probeKeyInput(baseUrl)), "status");
            assertProbe(server.call(id++, "probe", "reset", probeKeyInput(baseUrl)), "reset");
            assertProbe(server.call(id++, "probe", "wait_for_hit", waitInput(baseUrl)), "wait_for_hit");
            assertProbe(server.call(id++, "probe", "capture", captureInput(baseUrl)), "capture");
            assertProbe(server.call(id++, "probe", "actuate", actuateInput(baseUrl)), "actuate");
            assertProbe(server.call(id++, "probe", "profiler", profilerInput(baseUrl)), "profiler");
            JsonNode discovery = server.call(id++, "jvm_lifecycle", "list_jvms", Map.of());
            assertThat(discovery.path("resultType").asText()).isEqualTo("jvm_list");
            assertThat(discovery.path("reasonCode").asText()).isNotBlank();
            assertBlockedMutation(server.call(id++, "jvm_lifecycle", "attach", lifecycleInput()));
            assertBlockedMutation(server.call(id, "jvm_lifecycle", "deactivate", lifecycleInput()));
        } finally {
            sidecar.stop(0);
        }
    }

    @Test
    void packagedServerAttachesObservesStrictLineHitAndDeactivates() throws Exception {
        try (LineHitTargetProcess target = LineHitTargetProcess.start();
                ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            JsonNode discovery = server.call(2, "jvm_lifecycle", "list_jvms", Map.of());
            assertThat(discovery.path("reasonCode").asText()).isEqualTo("jvm_discovery_unverified");
            assertThat(target.isAlive()).isTrue();

            int probePort = freePort();
            Map<String, Object> lifecycleInput = Map.of(
                    "pid", Long.toString(target.pid()),
                    "expectedProcessStartEpochMs", target.processStartEpochMs(),
                    "confirm", true);
            Map<String, Object> attachInput = new java.util.LinkedHashMap<>(lifecycleInput);
            attachInput.put("probeHost", "127.0.0.1");
            attachInput.put("probePort", probePort);
            attachInput.put("include", "com.nimbly.mcpjavadevtools.server.compatibility.operation.**");
            JsonNode attach = server.call(3, "jvm_lifecycle", "attach", attachInput);
            assertThat(attach.path("status").asText()).as(attach.toString()).isEqualTo("ok");
            assertThat(attach.path("reasonCode").asText()).isEqualTo("active");
            assertThat(target.isAlive()).isTrue();

            String baseUrl = "http://127.0.0.1:" + probePort;
            assertProbe(server.call(4, "probe", "check", target(baseUrl)), "check");
            assertProbe(server.call(5, "probe", "reset", Map.of(
                    "baseUrl", baseUrl, "timeoutMs", 2_000, "key", target.key())), "reset");
            JsonNode wait = server.call(6, "probe", "wait_for_hit", Map.of(
                    "baseUrl", baseUrl, "timeoutMs", 5_000, "pollIntervalMs", 100,
                    "maxRetries", 3, "key", target.key()));
            assertThat(wait.path("status").asText()).as(wait.toString()).isEqualTo("ok");
            assertThat(wait.at("/result/outcome").asText()).isEqualTo("LINE_HIT");
            assertThat(wait.at("/result/observedHitCount").asLong()).isPositive();

            JsonNode deactivate = server.call(7, "jvm_lifecycle", "deactivate", lifecycleInput);
            assertThat(deactivate.path("status").asText()).as(deactivate.toString()).isEqualTo("ok");
            assertThat(deactivate.path("reasonCode").asText()).isEqualTo("deactivated");
            assertThat(deactivate.at("/lifecycle/outcome").asText()).isEqualTo("deactivated");
            JsonNode nonRestorable = deactivate.at("/lifecycle/nonRestorableClasses");
            assertThat(nonRestorable.isMissingNode()
                    || nonRestorable.isArray() && nonRestorable.isEmpty()).isTrue();
            assertThat(target.isAlive()).isTrue();
            JsonNode postDeactivate = server.call(8, "probe", "check", target(baseUrl));
            assertThat(postDeactivate.path("status").asText()).as(postDeactivate.toString())
                    .isEqualTo("diagnose_failed");
            assertThat(postDeactivate.path("reasonCode").asText()).isEqualTo("diagnose_failed");
            assertThat(postDeactivate.at("/result/reset/status").asText()).isEqualTo("UNREACHABLE");
            assertThat(postDeactivate.at("/result/status/status").asText()).isEqualTo("UNREACHABLE");
            assertThat(postDeactivate.at("/result/status/responseKey").isMissingNode()
                    || postDeactivate.at("/result/status/responseKey").isNull()).isTrue();
        }
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-620-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                "params", Map.of()));
    }

    private static void assertInventory(JsonNode response) {
        JsonNode probe = findTool(response, "probe");
        JsonNode lifecycle = findTool(response, "jvm_lifecycle");
        assertThat(probe).isNotNull();
        assertThat(lifecycle).isNotNull();
        assertReleasedActionSchema(probe, Set.of("baseUrl", "key", "captureId", "sessionId"));
        assertReleasedActionSchema(lifecycle, Set.of("pid", "expectedProcessStartEpochMs", "confirm"));
        assertThat(PROBE_ACTIONS).isEqualTo(java.util.Arrays.stream(ProbeAction.values())
                .map(ProbeAction::value).collect(Collectors.toSet()));
        assertThat(JVM_ACTIONS).isEqualTo(java.util.Arrays.stream(JvmLifecycleAction.values())
                .map(JvmLifecycleAction::value).collect(Collectors.toSet()));
    }

    private static void assertReleasedActionSchema(JsonNode tool, Set<String> inputFields) {
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("properties").path("action").path("type").asText())
                .isEqualTo("string");
        assertThat(schema.path("required")).containsExactlyInAnyOrder(
                JSON.getNodeFactory().textNode("action"), JSON.getNodeFactory().textNode("input"));
        JsonNode properties = schema.path("properties").path("input").path("properties");
        assertThat(inputFields).allSatisfy(field -> assertThat(properties.has(field)).isTrue());
    }

    private static JsonNode findTool(JsonNode response, String name) {
        for (JsonNode tool : response.path("result").path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return null;
    }

    private static void assertProbe(JsonNode payload, String action) {
        assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo("ok");
        assertThat(payload.path("reasonCode").asText()).as(payload.toString()).isEqualTo("success");
        assertThat(payload.toString()).doesNotContain("Bearer", "secret-value");
        switch (action) {
            case "check" -> assertThat(payload.at("/result/status/responseKey").asText())
                    .isEqualTo("mcp.jvm.diagnose#key");
            case "status" -> assertThat(payload.at("/result/entries/0/hitCount").asLong()).isPositive();
            case "reset" -> assertThat(payload.at("/result/entries/0/reset").asBoolean()).isTrue();
            case "wait_for_hit" -> assertThat(payload.at("/result/outcome").asText()).isEqualTo("LINE_HIT");
            case "capture" -> assertThat(payload.at("/result/found").asBoolean()).isTrue();
            case "actuate" -> assertThat(payload.at("/result/scopeState").asText()).isEqualTo("armed");
            case "profiler" -> assertThat(payload.at("/result/status").asText()).isEqualTo("idle");
            default -> fail("unexpected Probe action " + action);
        }
    }

    private static void assertBlockedMutation(JsonNode payload) {
        assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo("blocked");
        assertThat(payload.path("reasonCode").asText()).as(payload.toString()).isNotBlank();
        assertThat(payload.toString()).doesNotContain("Bearer", "secret-value");
    }

    private static Map<String, Object> target(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "http", Map.of("headers", Map.of("Authorization", "Bearer secret-value")));
    }

    private static Map<String, Object> probeKeyInput(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "key", "com.example.Sample#run:42");
    }

    private static Map<String, Object> waitInput(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "pollIntervalMs", 100, "maxRetries", 1,
                "key", "com.example.Sample#run:42");
    }

    private static Map<String, Object> captureInput(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "captureId", "capture-1");
    }

    private static Map<String, Object> actuateInput(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "action", "arm", "sessionId", "session-1",
                "targetKey", "com.example.Sample#run:42", "returnBoolean", true, "ttlMs", 1000);
    }

    private static Map<String, Object> profilerInput(String baseUrl) {
        return Map.of("baseUrl", baseUrl, "timeoutMs", 1_000,
                "action", "status");
    }

    private static HttpServer sidecar() throws IOException {
        HttpServer sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext("/__probe/reset", exchange -> {
            String key = JSON.readTree(exchange.getRequestBody()).path("key").asText();
            respond(exchange, "{\"key\":\"" + key
                    + "\",\"ok\":true,\"lineResolvable\":true,\"lineValidation\":\"resolvable\"}");
        });
        sidecar.createContext("/__probe/status", exchange -> {
            String key = queryValue(exchange, "key");
            long hitCount = System.currentTimeMillis();
            respond(exchange, "{\"probe\":{\"key\":\"" + key + "\",\"hitCount\":"
                    + hitCount + ",\"lastHitEpoch\":" + hitCount + ",\"lineResolvable\":true,"
                    + "\"lineValidation\":\"resolvable\"}}");
        });
        sidecar.createContext("/__probe/capture", exchange -> respond(exchange,
                "{\"capture\":{\"captureId\":\"capture-1\",\"methodKey\":\"com.example.Sample#run\","
                        + "\"capturedAtEpoch\":1,\"args\":[{\"value\":\"secret-value\"}],"
                        + "\"returnValue\":{\"value\":\"secret-value\"},\"executionPaths\":[\"safe\"]}}"));
        sidecar.createContext("/__probe/actuate", exchange -> respond(exchange,
                "{\"ok\":true,\"action\":\"arm\",\"sessionId\":\"session-1\","
                        + "\"targetKey\":\"com.example.Sample#run:42\",\"returnBoolean\":true,"
                        + "\"ttlMs\":1000,\"scopeState\":\"armed\",\"mode\":\"actuate\","
                        + "\"diagnostic\":\"secret-value\"}"));
        sidecar.createContext("/__probe/profiler", exchange -> respond(exchange,
                "{\"ok\":true,\"profiler\":{\"status\":\"idle\",\"supported\":true,"
                        + "\"diagnostic\":\"secret-value\"}}"));
        return sidecar;
    }

    private static String queryValue(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return "";
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, Object> lifecycleInput() {
        return Map.of("pid", "999999999", "expectedProcessStartEpochMs", 1, "confirm", true);
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Long-lived fixture whose returned source line is also its strict Probe key. */
    public static final class LineHitTarget {

        private LineHitTarget() {
        }

        public static void main(String[] args) throws Exception {
            int line = exercise();
            System.out.println(LineHitTarget.class.getName() + "#exercise:" + line);
            System.out.flush();
            while (true) {
                exercise();
                Thread.sleep(100);
            }
        }

        private static int exercise() {
            return new Throwable().getStackTrace()[0].getLineNumber();
        }
    }

    private static final class LineHitTargetProcess implements AutoCloseable {
        private final Process process;
        private final String key;

        private LineHitTargetProcess(Process process, String key) {
            this.process = process;
            this.key = key;
        }

        static LineHitTargetProcess start() throws IOException {
            String executable = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "java.exe" : "java";
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), LineHitTarget.class.getName())
                    .redirectErrorStream(true).start();
            String key = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (key == null || key.isBlank()) {
                process.destroyForcibly();
                throw new IOException("Line-hit target did not publish its Probe key");
            }
            return new LineHitTargetProcess(process, key);
        }

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        String key() {
            return key;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            assertThat(process.isAlive()).isFalse();
        }
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
            String javaBinary = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            ProcessBuilder builder = new ProcessBuilder(
                    javaBinary,
                    "-jar", jar.toString(), "--workspace-root=" + workspace)
                    .directory(workspace.toFile());
            builder.environment().put("MCP_JAVA_BIN", javaBinary);
            builder.environment().put("MCP_JAVA_ATTACH_HELPER_JAR",
                    jar.getParent().resolve("sidecar/jvm-attach-helper.jar").toString());
            builder.environment().put("MCP_JAVA_AGENT_JAR",
                    jar.getParent().resolve("sidecar/sidecar-agent.jar").toString());
            Process process = builder.start();
            return new ServerProcess(process, workspace);
        }

        JsonNode call(int id, String tool, String action, Map<String, Object> input)
                throws Exception {
            send(request(id, "tools/call", Map.of("name", tool,
                    "arguments", Map.of("action", action, "input", input))));
            JsonNode response = responseFor(id);
            assertThat(response.path("result").path("isError").asBoolean())
                    .as(response.toString()).isFalse();
            return JSON.readTree(response.path("result").path("content").get(0).path("text").asText());
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
                    .put("uri", workspaceUri).put("name", "mcpjvm-620-workspace");
            send(JSON.convertValue(response, new TypeReference<>() { }));
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
            } else if (!protocol) {
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
