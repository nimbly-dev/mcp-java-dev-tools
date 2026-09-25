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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
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
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.send(initializeRequest());
            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            JsonNode tools = server.responseFor(2).path("result").path("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools).extracting(tool -> tool.path("name").asText())
                    .containsExactlyInAnyOrder("operation_catalog", "operation_describe", "operation_execute");
            for (JsonNode tool : tools) {
                assertThat(tool.has("outputSchema")).isTrue();
            }

            JsonNode catalog = findTool(tools, "operation_catalog");
            JsonNode describe = findTool(tools, "operation_describe");
            JsonNode execute = findTool(tools, "operation_execute");
            assertThat(catalog.path("inputSchema").path("properties").has("query")).isTrue();
            assertThat(catalog.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(describe.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(execute.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(execute.path("inputSchema").path("properties").has("arguments")).isTrue();

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.exitCode()).isZero();
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
    void executableJarUsesCdeJvmLifecycleAgainstSocialPlatformUserApp() throws Exception {
        Path fixtureJar = repositoryRoot().resolve(
                "test/fixtures/spring-apps/social-platform/user-service/user-app/target/"
                        + "user-app-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(Files.isRegularFile(fixtureJar),
                "Package the social-platform user-app fixture before the CDE lifecycle acceptance test.");
        Path serverJar = jarPath();
        List<Map<String, Object>> transcript = new ArrayList<>();
        try (SpringFixtureTargetProcess target = SpringFixtureTargetProcess.start(fixtureJar);
                McpServerProcess server = McpServerProcess.start(serverJar, workspaceRoot())) {
            target.awaitHealthy();

            Map<String, Object> initialize = initializeRequest();
            server.send(initialize);
            JsonNode initializeResponse = server.responseFor(1);
            assertThat(initializeResponse.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);
            transcript.add(Map.of("request", initialize, "response", initializeResponse));
            Map<String, Object> initialized = Map.of(
                    "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of());
            server.send(initialized);

            Map<String, Object> toolsListRequest = request(2, "tools/list", Map.of());
            server.send(toolsListRequest);
            JsonNode toolsListResponse = server.responseFor(2);
            JsonNode tools = toolsListResponse.path("result").path("tools");
            List<String> toolNames = new ArrayList<>();
            for (JsonNode tool : tools) {
                toolNames.add(tool.path("name").asText());
                assertThat(tool.has("outputSchema")).isTrue();
            }
            assertThat(toolNames).containsExactlyInAnyOrder(
                    "operation_catalog", "operation_describe", "operation_execute");
            JsonNode catalogTool = findTool(tools, "operation_catalog");
            JsonNode describeTool = findTool(tools, "operation_describe");
            JsonNode executeTool = findTool(tools, "operation_execute");
            assertThat(catalogTool.path("inputSchema").path("properties").has("query")).isTrue();
            assertThat(catalogTool.path("inputSchema").path("properties").has("limit")).isTrue();
            assertThat(catalogTool.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(describeTool.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(describeTool.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(executeTool.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("arguments")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("confirmed")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("input")).isFalse();
            transcript.add(Map.of("request", toolsListRequest, "response", toolsListResponse));

            Map<String, Object> catalogRequest = request(3, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of("query", "jvm_lifecycle")));
            server.send(catalogRequest);
            JsonNode catalogResponse = server.responseFor(3);
            JsonNode catalog = toolPayload(catalogResponse);
            assertThat(catalog.path("status").asText()).isEqualTo("succeeded");
            List<String> operationIds = new ArrayList<>();
            for (JsonNode entry : catalog.path("entries")) {
                operationIds.add(entry.path("operationId").asText());
            }
            assertThat(operationIds).containsExactlyInAnyOrder(
                    "jvm_lifecycle.list_jvms", "jvm_lifecycle.attach", "jvm_lifecycle.deactivate");
            transcript.add(Map.of("request", catalogRequest, "response", catalogResponse));

            Map<String, Object> zeroLimitCatalogRequest = request(13, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of("limit", 0)));
            server.send(zeroLimitCatalogRequest);
            JsonNode zeroLimitCatalogResponse = server.responseFor(13);
            JsonNode zeroLimitCatalog = toolPayload(zeroLimitCatalogResponse);
            assertThat(zeroLimitCatalog.path("status").asText()).isEqualTo("invalid_input");
            assertThat(zeroLimitCatalog.path("reasonCode").asText()).isEqualTo("catalog_limit_invalid");
            transcript.add(Map.of("request", zeroLimitCatalogRequest, "response", zeroLimitCatalogResponse));

            Map<String, Object> describeRequest = request(4, "tools/call", Map.of(
                    "name", "operation_describe",
                    "arguments", Map.of("operationId", "jvm_lifecycle.attach")));
            server.send(describeRequest);
            JsonNode describeResponse = server.responseFor(4);
            JsonNode description = toolPayload(describeResponse);
            JsonNode details = description.path("operation");
            assertThat(description.path("status").asText()).isEqualTo("succeeded");
            assertThat(details.path("safety").path("confirmationRequired").asBoolean()).isTrue();
            assertThat(details.path("inputSchema").path("properties").has("confirm")).isFalse();
            List<String> documentedArguments = new ArrayList<>();
            for (JsonNode argument : details.path("arguments")) {
                documentedArguments.add(argument.path("name").asText());
            }
            assertThat(documentedArguments).contains("pid", "expectedProcessStartEpochMs")
                    .doesNotContain("confirm");
            assertThat(description.toString().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("deprecated", "replacement", "alias");
            transcript.add(Map.of("request", describeRequest, "response", describeResponse));

            Map<String, Object> listRequest = request(5, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "jvm_lifecycle.list_jvms", "arguments", Map.of())));
            server.send(listRequest);
            JsonNode listResponse = server.responseFor(5);
            JsonNode discovery = toolPayload(listResponse);
            assertThat(discovery.path("status").asText()).isEqualTo("succeeded");
            JsonNode candidates = discovery.path("result").path("actionResult").path("jvms");
            JsonNode selected = null;
            for (JsonNode candidate : candidates) {
                if (Long.toString(target.pid()).equals(candidate.path("pid").asText())) {
                    selected = candidate;
                    break;
                }
            }
            assertThat(candidates.isArray()).isTrue();
            assertThat(selected)
                    .as("list_jvms must return the fixture candidate")
                    .isNotNull();
            boolean fixtureDiscovered = true;
            String selectedPid = selected.path("pid").asText();
            long selectedProcessStartEpochMs = selected.path("processStartEpochMs").asLong();
            assertThat(selectedPid).isEqualTo(Long.toString(target.pid()));
            assertThat(selectedProcessStartEpochMs).isEqualTo(target.processStartEpochMs());
            transcript.add(Map.of("request", listRequest, "response", listResponse));

            int probePort = freePort();
            Map<String, Object> attachArguments = Map.of(
                    "pid", selectedPid,
                    "expectedProcessStartEpochMs", selectedProcessStartEpochMs,
                    "probeHost", "127.0.0.1",
                    "probePort", probePort);
            Map<String, Object> confirmationRequest = request(6, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", attachArguments,
                            "confirmed", false)));
            server.send(confirmationRequest);
            JsonNode confirmationResponse = server.responseFor(6);
            JsonNode confirmation = toolPayload(confirmationResponse);
            assertThat(confirmation.path("status").asText()).isEqualTo("confirmation_required");
            assertThat(target.awaitSidecarReachability(probePort, false, Duration.ofSeconds(1))).isTrue();
            transcript.add(Map.of("request", confirmationRequest, "response", confirmationResponse));

            Map<String, Object> attachRequest = request(7, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", attachArguments,
                            "confirmed", true)));
            server.send(attachRequest);
            JsonNode attachResponse = server.responseFor(7, Duration.ofSeconds(90));
            JsonNode attach = toolPayload(attachResponse);
            assertThat(attach.path("status").asText()).isEqualTo("succeeded");
            assertThat(attach.path("result").path("reasonCode").asText()).isEqualTo("active");
            assertThat(attach.path("result").path("actionResult").path("outcome").asText())
                    .isEqualTo("active");
            assertThat(target.awaitSidecarReachability(probePort, true, Duration.ofSeconds(15))).isTrue();
            assertThat(target.isAlive()).isTrue();
            transcript.add(Map.of("request", attachRequest, "response", attachResponse));

            Map<String, Object> deactivateRequest = request(8, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.deactivate",
                            "arguments", Map.of(
                                    "pid", selectedPid,
                                    "expectedProcessStartEpochMs", selectedProcessStartEpochMs),
                            "confirmed", true)));
            server.send(deactivateRequest);
            JsonNode deactivateResponse = server.responseFor(8, Duration.ofSeconds(90));
            JsonNode deactivate = toolPayload(deactivateResponse);
            assertThat(deactivate.path("status").asText()).isEqualTo("succeeded");
            assertThat(deactivate.path("result").path("reasonCode").asText()).isEqualTo("deactivated");
            assertThat(deactivate.path("result").path("actionResult").path("outcome").asText())
                    .isEqualTo("deactivated");
            assertThat(target.awaitSidecarReachability(probePort, false, Duration.ofSeconds(15))).isTrue();
            assertThat(target.isHealthy()).isTrue();
            transcript.add(Map.of("request", deactivateRequest, "response", deactivateResponse));

            Map<String, Object> invalidTargetRequest = request(9, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", Map.of(
                                    "pid", "not-a-pid", "expectedProcessStartEpochMs", 1),
                            "confirmed", true)));
            server.send(invalidTargetRequest);
            JsonNode invalidTargetResponse = server.responseFor(9);
            JsonNode invalidTarget = toolPayload(invalidTargetResponse);
            assertThat(invalidTarget.path("status").asText()).isEqualTo("invalid_input");
            assertThat(invalidTarget.toString()).doesNotContain(repositoryRoot().toString());
            transcript.add(Map.of("request", invalidTargetRequest, "response", invalidTargetResponse));

            Map<String, Object> unknownOperationRequest = request(10, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "unknown.operation", "arguments", Map.of())));
            server.send(unknownOperationRequest);
            JsonNode unknownOperationResponse = server.responseFor(10);
            JsonNode unknownOperation = toolPayload(unknownOperationResponse);
            assertThat(unknownOperation.path("status").asText()).isEqualTo("unsupported_operation");
            assertThat(unknownOperation.toString()).doesNotContain(repositoryRoot().toString());
            transcript.add(Map.of("request", unknownOperationRequest, "response", unknownOperationResponse));

            Map<String, Object> malformedOperationRequest = request(11, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "invalid id", "arguments", Map.of())));
            server.send(malformedOperationRequest);
            JsonNode malformedOperationResponse = server.responseFor(11);
            JsonNode malformedOperation = toolPayload(malformedOperationResponse);
            assertThat(malformedOperation.path("status").asText()).isEqualTo("unsupported_operation");
            assertThat(malformedOperation.path("operationId").isNull()).isTrue();
            assertThat(malformedOperation.path("result").isNull()).isTrue();
            transcript.add(Map.of("request", malformedOperationRequest, "response", malformedOperationResponse));

            Map<String, Object> unknownDescriptionRequest = request(12, "tools/call", Map.of(
                    "name", "operation_describe",
                    "arguments", Map.of("operationId", "invalid id")));
            server.send(unknownDescriptionRequest);
            JsonNode unknownDescriptionResponse = server.responseFor(12);
            JsonNode unknownDescription = toolPayload(unknownDescriptionResponse);
            assertThat(unknownDescription.path("status").asText()).isEqualTo("unsupported");
            assertThat(unknownDescription.path("operation").isNull()).isTrue();
            transcript.add(Map.of("request", unknownDescriptionRequest, "response", unknownDescriptionResponse));

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.exitCode()).isZero();

            Path report = serverJar.getParent().resolve("mcpjvm-633-stdio-acceptance.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.ofEntries(
                    Map.entry("javaVersion", System.getProperty("java.version")),
                    Map.entry("serverCommand", List.of(
                            McpServerProcess.javaBinary(), "-jar", serverJar.toString())),
                    Map.entry("fixtureCommand", List.of(
                            McpServerProcess.javaBinary(), "-jar", fixtureJar.toString(),
                            "--server.port=" + target.port())),
                    Map.entry("tools", toolNames),
                    Map.entry("mcpTranscript", transcript),
                    Map.entry("fixtureDiscoveredByListJvms", fixtureDiscovered),
                    Map.entry("acceptanceGap", fixtureDiscovered ? "none"
                            : "The list_jvms response contained no candidate for the running fixture."),
                    Map.entry("sidecar", Map.of("host", "127.0.0.1", "port", probePort,
                            "reachableAfterAttach", true, "reachableAfterDeactivate", false)),
                    Map.entry("fixtureHealthAfterDeactivate", true),
                    Map.entry("stdoutContainsOnlyJsonRpc", true),
                    Map.entry("serverExitCode", server.exitCode())));
            assertThat(fixtureDiscovered)
                    .as("list_jvms must return the fixture candidate; see %s", report)
                    .isTrue();
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

    private static JsonNode findTool(JsonNode tools, String toolName) {
        for (JsonNode tool : tools) {
            if (toolName.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return null;
    }


    private static Path repositoryRoot() {
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

    private static Path jarPath() {
        String configured = System.getProperty("mcpServerJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property mcpServerJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static Path testClassesPath() {
        try {
            return Path.of(McpServerStdioIT.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Test classes location is not a valid URI", exception);
        }
    }

    private static Path workspaceRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "mcp-java-dev-tools-stdio-it-workspace");
    }











    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
        try {
            return JSON.readTree(text);
        } catch (IOException exception) {
            throw new AssertionError("Probe Tool payload was not JSON: " + text, exception);
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

    private static final class SpringFixtureTargetProcess implements AutoCloseable {

        private final Process process;
        private final int port;
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build();

        private SpringFixtureTargetProcess(Process process, int port) {
            this.process = process;
            this.port = port;
        }

        static SpringFixtureTargetProcess start(Path fixtureJar) throws IOException {
            int port = freePort();
            Process process = new ProcessBuilder(
                    McpServerProcess.javaBinary(), "-jar", fixtureJar.toString(), "--server.port=" + port)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new SpringFixtureTargetProcess(process, port);
        }

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        int port() {
            return port;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        boolean isHealthy() throws IOException, InterruptedException {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && JSON.readTree(response.body()).path("status").asText()
                    .equalsIgnoreCase("up");
        }

        void awaitHealthy() throws Exception {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    fail("Spring social-platform fixture exited before readiness.");
                }
                try {
                    if (isHealthy()) {
                        return;
                    }
                } catch (IOException ignored) {
                    // The Spring fixture is still starting its embedded server.
                }
                Thread.sleep(250);
            }
            fail("Spring social-platform fixture did not become healthy within 30 seconds.");
        }

        boolean awaitSidecarReachability(int sidecarPort, boolean expected, Duration timeout) throws Exception {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                if (sidecarReachable(sidecarPort) == expected) {
                    return true;
                }
                Thread.sleep(250);
            }
            return false;
        }

        private boolean sidecarReachable(int sidecarPort) throws InterruptedException {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + sidecarPort
                                        + "/__probe/status?key=mcpjvm633.acceptance.HealthCheck%23status%3A1"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (IOException ignored) {
                return false;
            }
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            assertThat(process.isAlive()).isFalse();
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
            return startProcess(command, workspaceRoot);
        }

        private static McpServerProcess startProcess(List<String> command, Path workspaceRoot) throws IOException {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            return new McpServerProcess(process, workspaceRoot);
        }

        void send(Map<String, Object> message) throws IOException {
            try {
                stdin.write(JSON.writeValueAsBytes(message));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException failure) {
                throw new IOException(
                        "Could not write to the MCP server process (alive=" + process.isAlive()
                                + ", stderr=" + stderrText() + ").",
                        failure);
            }
        }

        void send(JsonNode message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode responseFor(int id) throws Exception {
            return responseFor(id, RESPONSE_TIMEOUT);
        }

        JsonNode responseFor(int id, Duration timeout) throws Exception {
            Instant deadline = Instant.now().plus(timeout);
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

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        int exitCode() {
            return process.exitValue();
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
