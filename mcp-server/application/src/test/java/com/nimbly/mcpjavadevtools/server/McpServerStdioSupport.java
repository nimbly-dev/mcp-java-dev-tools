package com.nimbly.mcpjavadevtools.server;

import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.CORRELATION_PLAN_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.CROSS_PLAN_CONSUMER_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_CONSUMER_LISTENER;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_CONSUMER_PAYLOAD;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_CONSUMER_PROBE_ID;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.EVENT_PRODUCER_PROBE_ID;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.LOOPBACK_HTTP_PREFIX;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.PROTOCOL_VERSION;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.USER_PROFILE_LINE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Shared process, fixture, JSON-RPC, and artifact helpers for packaged MCP acceptance. */
abstract class McpServerStdioSupport {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    protected static Map<String, Object> initializeRequest() {
        return request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcp-server-foundation-it", "version", "1.0")));
    }

    protected static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    protected static Map<String, Object> executeRequest(
            int id, String operationId, Map<String, Object> arguments, boolean confirmed) {
        return request(id, "tools/call", Map.of(
                "name", "operation_execute",
                "arguments", Map.of(
                        "operationId", operationId,
                        "arguments", arguments,
                        "confirmed", confirmed)));
    }

    protected static void initialize(McpServerProcess server) throws Exception {
        server.send(initializeRequest());
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized",
                "params", Map.of()));
    }

    protected static Map<String, Object> performanceContract(int targetPort, String probeBaseUrl) {
        return Map.of(
                "suiteType", "performance",
                "workloadProvider", Map.of(
                        "type", "jmeter",
                        "mode", "generated_http",
                        "options", Map.of("installationPath", jmeterHome().toString())),
                "entrypoints", List.of(Map.of(
                        "transport", Map.of(
                                "protocol", "http",
                                "baseUrl", LOOPBACK_HTTP_PREFIX + targetPort,
                                "healthCheckPath", "/actuator/health",
                                "wrappedOnly", true),
                        "request", Map.of(
                                "method", "GET",
                                "path", "/api/v1/users/alice"))),
                "observationTargets", Map.of(
                        "probeBaseUrl", probeBaseUrl,
                        "requiredLineHits", List.of(
                                USER_PROFILE_LINE_KEY)),
                "loadModel", Map.of(
                        "mode", "concurrency",
                        "concurrency", 1,
                        "rampUpSeconds", 0,
                        "durationSeconds", 2),
                "successCriteria", Map.of(
                        "maxErrorRatePct", 0,
                        "minThroughputPerSec", 0.1,
                        "p95LatencyMs", 5_000));
    }

    protected static Path jmeterHome() {
        String configured = System.getProperty("mcpjvm.acceptance.jmeterHome", "").trim();
        if (configured.isEmpty()) {
            configured = System.getenv().getOrDefault("JMETER_HOME", "").trim();
        }
        Path home = configured.isEmpty()
                ? repositoryRoot().getParent().resolve("tools/apache-jmeter-5.6.3")
                : Path.of(configured);
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "jmeter.bat"
                : "jmeter";
        assertThat(home.resolve("bin").resolve(executable))
                .as("Apache JMeter is required for packaged Performance Suite acceptance; "
                        + "set -Dmcpjvm.acceptance.jmeterHome or JMETER_HOME")
                .isRegularFile();
        return home.toAbsolutePath().normalize();
    }

    protected static Path copyRouteFixtureSource(Path workspace) throws IOException {
        Path fixture = repositoryRoot().resolve(
                "test/fixtures/spring-apps/social-platform/user-service/user-app");
        Path sourceRoot = fixture.resolve("src/main/java");
        Path copyRoot = workspace.resolve("route-fixture");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path destination = copyRoot.resolve(sourceRoot.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        return copyRoot;
    }

    protected static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    protected static JsonNode findTool(JsonNode tools, String toolName) {
        for (JsonNode tool : tools) {
            if (toolName.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return null;
    }


    protected static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("mcp-server/pom.xml"))
                    && Files.isDirectory(candidate.resolve("test/fixtures/spring-apps/social-platform"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root could not be located for fixture acceptance.");
    }

    protected static Path jarPath() {
        String configured = System.getProperty("mcpServerJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property mcpServerJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    protected static Path testClassesPath() {
        try {
            return Path.of(McpServerStdioSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Test classes location is not a valid URI", exception);
        }
    }

    protected static Path workspaceRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "mcp-java-dev-tools-stdio-it-workspace");
    }











    protected static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
    protected void assertPosixSignalStopsExecutable(String signal) throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.sendPosixSignal(signal);
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        }
    }

    protected static JsonNode toolPayload(JsonNode response) throws IOException {
        String text = response.path("result").path("content").get(0).path("text").asText();
        try {
            return JSON.readTree(text);
        } catch (IOException exception) {
            throw new AssertionError("Probe Tool payload was not JSON: " + text, exception);
        }
    }

    protected static void writeProbeRegistry(Path workspace, int producerPort, int consumerPort) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("defaultProfile", "local");
        ObjectNode probes = root.putObject("profiles").putObject("local").putObject("probes");
        probes.putObject(EVENT_PRODUCER_PROBE_ID)
                .put("baseUrl", LOOPBACK_HTTP_PREFIX + producerPort);
        probes.putObject(EVENT_CONSUMER_PROBE_ID)
                .put("baseUrl", LOOPBACK_HTTP_PREFIX + consumerPort);
        Path directory = workspace.resolve(".mcpjvm");
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("probe-config.json").toFile(), root);
    }

    protected static void attach(
            McpServerProcess server,
            int requestId,
            SpringFixtureTargetProcess target,
            int probePort,
            String include) throws Exception {
        server.send(executeRequest(requestId, "jvm_lifecycle.attach", Map.of(
                "pid", Long.toString(target.pid()),
                "expectedProcessStartEpochMs", target.processStartEpochMs(),
                "probeHost", "127.0.0.1",
                "probePort", probePort,
                "include", include), true));
        JsonNode attached = toolPayload(server.responseFor(requestId, Duration.ofSeconds(90)));
        assertThat(attached.path("status").asText()).isEqualTo("succeeded");
        assertThat(attached.path("result").path("actionResult").path("outcome").asText())
                .as(attached.toPrettyString())
                .isEqualTo("active");
    }

    protected static Map<String, Object> eventCorrelationPlan(int producerPort, String sessionId, String notes) {
        return eventCorrelationPlan(producerPort, sessionId, notes, false);
    }

    protected static Map<String, Object> eventCorrelationPlan(
            int producerPort, String sessionId, String notes, boolean crossPlan) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("context", "entities");
        body.put("type", "TriggerIndex");
        body.put("groupId", "group-001");
        body.put("source", "mcpjvm-644-java-acceptance");
        body.put("dataFormatVersion", 1);
        body.put("dataId", "tenant-batch-01");
        body.put("data", List.of("tenant-social-001"));
        if (notes != null) {
            body.put("notes", notes);
        }
        Map<String, Object> correlation = Map.ofEntries(
                Map.entry("enabled", true),
                Map.entry("crossPlan", crossPlan),
                Map.entry("correlationSessionId", sessionId),
                Map.entry("key", Map.of(
                        "type", "messageId",
                        "source", Map.of(
                                "type", "json_path",
                                "path", "response.bodyJson.eventId"))),
                Map.entry("window", Map.of("maxWindowMs", 10_000)),
                Map.entry("probeIds", List.of(EVENT_PRODUCER_PROBE_ID, EVENT_CONSUMER_PROBE_ID)),
                Map.entry("expectedFlow", List.of(EVENT_PRODUCER_PROBE_ID, EVENT_CONSUMER_PROBE_ID)),
                Map.entry("runtimeEvidence", Map.of(
                        "required", true,
                        "probeIds", List.of(EVENT_CONSUMER_PROBE_ID),
                        "eventKeyPath", "$.eventId",
                        "pageLimit", 64,
                        "maxEvents", 256,
                        "maxDurationMs", 2_000)),
                Map.entry("consumerBoundaries", List.of(Map.of(
                        "id", "event-queue-listener",
                        "selector", Map.of(
                                "fqcn", EVENT_CONSUMER_LISTENER,
                                "method", "receiveEvent",
                                "parameterTypes", List.of(
                                        EVENT_CONSUMER_PAYLOAD)),
                        "eventArgumentIndex", 0))));
        Map<String, Object> contract = Map.of(
                "prerequisites", List.of(Map.of(
                        "key", "authorization",
                        "required", true,
                        "secret", true,
                        "provisioning", "user_input")),
                "targets", List.of(Map.of(
                        "id", "event-producer",
                        "runtimeVerification", Map.of("probeId", EVENT_PRODUCER_PROBE_ID))),
                "steps", List.of(Map.ofEntries(
                        Map.entry("order", 1),
                        Map.entry("id", "producer_trigger_event"),
                        Map.entry("targetRef", 0),
                        Map.entry("protocol", "http"),
                        Map.entry("transport", Map.of("http", Map.of(
                                "method", "POST",
                                "url", LOOPBACK_HTTP_PREFIX + producerPort + "/api/v1/events/trigger",
                                "headers", Map.of(
                                        "Authorization", "${authorization}",
                                        "Content-Type", "application/json"),
                                "body", body,
                                "timeoutMs", 10_000))),
                        Map.entry("expect", List.of(
                                Map.of(
                                        "id", "accepted",
                                        "actualPath", "response.statusCode",
                                        "operator", "field_equals",
                                        "expected", 200),
                                Map.of(
                                        "id", "event-id",
                                        "actualPath", "response.bodyJson.eventId",
                                        "operator", "field_exists"))))),
                "correlation", correlation);
        return Map.of(
                "metadata", Map.of("execution", Map.of(
                        "intent", "regression",
                        "probeVerification", false,
                        "pinStrictProbeKey", false)),
                "contract", contract,
                "plan", CORRELATION_PLAN_TEXT);
    }

    protected static Map<String, Object> eventConsumerCorrelationPlan(int consumerPort, String sessionId) {
        String token = "${suite.correlation." + sessionId + ".keyValue}";
        Map<String, Object> correlation = Map.ofEntries(
                Map.entry("enabled", true),
                Map.entry("crossPlan", true),
                Map.entry("correlationSessionId", sessionId),
                Map.entry("key", Map.of("type", "messageId", "value", token)),
                Map.entry("window", Map.of("maxWindowMs", 10_000)),
                Map.entry("probeIds", List.of(EVENT_PRODUCER_PROBE_ID, EVENT_CONSUMER_PROBE_ID)),
                Map.entry("expectedFlow", List.of(EVENT_PRODUCER_PROBE_ID, EVENT_CONSUMER_PROBE_ID)),
                Map.entry("runtimeEvidence", Map.of("required", false)));
        Map<String, Object> step = Map.ofEntries(
                Map.entry("order", 1),
                Map.entry("id", "consumer_status"),
                Map.entry("targetRef", 0),
                Map.entry("protocol", "http"),
                Map.entry("transport", Map.of("http", Map.of(
                        "method", "GET",
                        "url", LOOPBACK_HTTP_PREFIX + consumerPort + "/internal/events/" + token,
                        "timeoutMs", 10_000))),
                Map.entry("expect", List.of(Map.of(
                        "id", "processed",
                        "actualPath", "response.statusCode",
                        "operator", "field_equals",
                        "expected", 200))));
        Map<String, Object> contract = Map.of(
                "targets", List.of(Map.of(
                        "id", "event-consumer",
                        "runtimeVerification", Map.of("probeId", EVENT_CONSUMER_PROBE_ID))),
                "steps", List.of(step),
                "correlation", correlation);
        return Map.of(
                "metadata", Map.of("execution", Map.of(
                        "intent", "regression",
                        "probeVerification", false,
                        "pinStrictProbeKey", false)),
                "contract", contract,
                "plan", CROSS_PLAN_CONSUMER_TEXT);
    }

    protected static void assertConsumerProcessed(int consumerPort, String eventId) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(
                                LOOPBACK_HTTP_PREFIX + consumerPort + "/internal/events/" + eventId))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200
                        && "processed".equals(JSON.readTree(response.body()).path("status").asText())) {
                    return;
                }
            } catch (IOException ignored) {
                // The async consumer has not published its result yet.
            }
            Thread.sleep(100);
        }
        fail("Consumer did not process correlated event " + eventId + " within 10 seconds.");
    }


















    protected void assertJsonRpcMessage(String line) {
        try {
            JsonNode message = JSON.readTree(line);
            assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        } catch (IOException exception) {
            fail("stdout line was not valid JSON-RPC: " + line, exception);
        }
    }
}
