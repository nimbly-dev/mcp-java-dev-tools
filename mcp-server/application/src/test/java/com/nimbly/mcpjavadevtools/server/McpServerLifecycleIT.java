package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.MultipleFailuresError;

@Tag("lifecycle")
class McpServerLifecycleIT extends McpServerStdioSupport {

    @Test
    @Tag("smoke")
    void catalogDescribeExecutePersistsARealArtifact() throws Exception {
        Path workspace = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(request(2, "tools/list", Map.of()));
            assertThat(server.responseFor(2).path("result").path("tools"))
                    .extracting(tool -> tool.path("name").asText())
                    .containsExactlyInAnyOrder("operation_catalog", "operation_describe", "operation_execute");
            Set<String> ids = new HashSet<>();
            String cursor = null;
            int requestId = 10;
            do {
                Map<String, Object> arguments = cursor == null ? Map.of("limit", 7)
                        : Map.of("limit", 7, "cursor", cursor);
                JsonNode page = call(server, requestId++, "operation_catalog", arguments);
                assertThat(page.path("status").asText()).isEqualTo("succeeded");
                assertThat(page.path("entries").size()).isBetween(1, 7);
                for (JsonNode entry : page.path("entries")) {
                    String id = entry.path("operationId").asText();
                    assertThat(ids.add(id)).as("No duplicate paginated operation").isTrue();
                    JsonNode description = call(server, requestId++, "operation_describe", Map.of("operationId", id));
                    assertThat(description.path("operation").path("operationId").asText()).isEqualTo(id);
                    assertThat(description.path("operation").path("inputSchema").isObject()).isTrue();
                }
                assertThat(ids.size()).isLessThanOrEqualTo(page.path("totalMatches").asInt());
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
                if (cursor == null) {
                    assertThat(ids).hasSize(page.path("totalMatches").asInt());
                }
            } while (cursor != null);
            assertThat(ids).contains("artifact_management.project_context.upsert", "jvm_lifecycle.list_jvms");
            JsonNode filtered = call(server, requestId++, "operation_catalog", Map.of("api", "jvm_lifecycle"));
            assertThat(filtered.path("entries")).hasSize(3)
                    .allSatisfy(entry -> assertThat(entry.path("api").asText()).isEqualTo("jvm_lifecycle"));
            String classification = filtered.path("entries").get(0).path("classification").asText();
            JsonNode classified = call(server, requestId++, "operation_catalog",
                    Map.of("api", "JVM_LIFECYCLE", "classification", classification));
            assertThat(classified.path("entries")).isNotEmpty().allSatisfy(entry ->
                    assertThat(entry.path("classification").asText()).isEqualTo(classification));
            upsertProject(server, requestId++, workspace);
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/lifecycle/projects.json").toFile())
                    .path("workspaces").get(0).path("projectRoot").asText()).isEqualTo(workspace.toString());
            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        }
    }

    @Test
    void invalidAndUnknownRequestsFailStructurallyAndRecover() throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            initialize(server);
            for (String id : List.of("bad id", "unknown.operation")) {
                assertThat(call(server, 10, "operation_describe", Map.of("operationId", id))
                        .path("reasonCode").asText()).isEqualTo("unsupported_operation");
                server.send(executeRequest(11, id, Map.of(), true));
                assertThat(toolPayload(server.responseFor(11)).path("reasonCode").asText())
                        .isEqualTo("unsupported_operation");
            }
            for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                    Map.of("limit", 0), Map.of("limit", 51), Map.of("cursor", "invalid"),
                    Map.of("query", "x".repeat(257)), Map.of("api", "x".repeat(129)),
                    Map.of("classification", "x".repeat(129)))) {
                assertThat(call(server, 12, "operation_catalog", invalid).path("status").asText())
                        .isEqualTo("invalid_input");
            }
            server.send(executeRequest(13, "artifact_management.project_context.upsert", Map.of(), true));
            assertThat(toolPayload(server.responseFor(13)).path("status").asText()).isEqualTo("invalid_input");
            assertThat(call(server, 14, "operation_catalog", Map.of("query", "list_jvms"))
                    .path("entries")).hasSize(1);
            assertThat(call(server, 15, "operation_catalog", Map.of("query", "no-such-operation"))
                    .path("totalMatches").asInt()).isZero();
        }
    }

    @Test
    void statusRootsRebindPropagatesAndDoesNotCrossProcesses() throws Exception {
        Path first = workspaceRoot();
        Path second = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), first);
                McpServerProcess isolated = McpServerProcess.start(jarPath(), second)) {
            initialize(server);
            initialize(isolated);
            JsonNode status = status(server, 20);
            assertSafeStatus(status, server, first);
            assertThat(server.rootsRequests()).isPositive();
            server.roots(List.of(second));
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed"));
            assertThat(status(server, 21).path("workspaceRoot").asText()).isEqualTo(second.toString());
            upsertProject(server, 22, second);
            assertThat(second.resolve(".mcpjvm/lifecycle/projects.json")).isRegularFile();
            assertThat(first.resolve(".mcpjvm/lifecycle/projects.json")).doesNotExist();
            isolated.roots(List.of(first));
            assertThat(status(isolated, 23).path("workspaceRoot").asText()).isEqualTo(first.toString());
            assertThat(status(server, 24).path("workspaceRoot").asText()).isEqualTo(second.toString());
        }
    }

    @Test
    void missingAmbiguousAndInvalidRootsHaveStableOutcomes() throws Exception {
        Path first = workspaceRoot();
        Path second = workspaceRoot();
        for (Path root : List.of(first, second)) {
            Files.createDirectories(root.resolve(".mcpjvm"));
            Files.writeString(root.resolve(".mcpjvm/probe-config.json"), "{}");
        }
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.roots(List.of());
            initialize(server);
            assertThat(status(server, 20).path("workspaceReasonCode").asText()).isEqualTo("workspace_context_missing");
            server.roots(List.of(first, second));
            assertThat(status(server, 21).path("workspaceReasonCode").asText()).isEqualTo("workspace_context_ambiguous");
            server.roots(List.of(first, first));
            assertThat(status(server, 22).path("workspaceRoot").asText()).isEqualTo(first.toString());
            server.rootUris(List.of("https://example.invalid", "not a URI"));
            assertThat(status(server, 23).path("workspaceReasonCode").asText()).isEqualTo("workspace_context_missing");
            server.roots(List.of(second));
            assertThat(status(server, 24).path("workspaceRoot").asText()).isEqualTo(second.toString());
            server.rootsUnavailable();
            assertThat(status(server, 25).path("rootsDiscoveryStatus").asText()).isEqualTo("unavailable");
            assertThat(status(server, 26).path("workspaceRoot").asText()).isEqualTo(second.toString());
        }
    }

    @Test
    void configuredArgumentPrecedesEnvironmentWithoutRoots() throws Exception {
        Path argument = workspaceRoot();
        Path environment = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.startWithEnvironment(jarPath(), workspaceRoot(),
                Map.of("MCP_WORKSPACE_ROOT", environment.toString()), "--workspace-root=" + argument)) {
            initializeWithoutRoots(server);
            assertThat(status(server, 20).path("workspaceRoot").asText()).isEqualTo(argument.toString());
        }
        try (McpServerProcess server = McpServerProcess.startWithEnvironment(jarPath(), workspaceRoot(),
                Map.of("MCP_WORKSPACE_ROOT", environment.toString()))) {
            initializeWithoutRoots(server);
            assertThat(status(server, 20).path("workspaceRoot").asText()).isEqualTo(environment.toString());
        }
    }

    @Test
    void sessionAndCwdFallbacksStayWithinScenarioState() throws Exception {
        Path session = workspaceRoot();
        for (Map<String, String> environment : List.of(
                Map.of("INIT_CWD", session.toString(), "PWD", workspaceRoot().toString()),
                Map.of("PWD", session.toString()))) {
            try (McpServerProcess server = McpServerProcess.startWithEnvironment(jarPath(), workspaceRoot(), environment)) {
                initializeWithoutRoots(server);
                JsonNode status = status(server, 20);
                assertThat(status.path("workspaceRoot").asText()).isEqualTo(session.toString());
                assertThat(status.path("workspaceRootSource").asText()).isEqualTo("session");
            }
        }
        Path cwd = workspaceRoot();
        Files.createDirectories(cwd.resolve(".mcpjvm"));
        Files.writeString(cwd.resolve(".mcpjvm/probe-config.json"), "{}");
        try (McpServerProcess server = McpServerProcess.start(jarPath(), cwd)) {
            initializeWithoutRoots(server);
            assertThat(status(server, 20).path("workspaceRootSource").asText()).isEqualTo("cwd");
        }
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            initializeWithoutRoots(server);
            assertThat(status(server, 20).path("workspaceReasonCode").asText()).isEqualTo("workspace_context_missing");
        }
    }

    @Test
    void ownedDescendantCleanupAndRealParentExitAreBounded() throws Exception {
        assertOwnedTreeCleanup();
        assertParentExitWithOpenStdin();
    }

    private void assertOwnedTreeCleanup() throws Exception {
        Process parent = startParent("tree", workspaceRoot(), 0);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(parent.getInputStream()))) {
            try {
                ProcessHandle child = readReportedChild(reader);
                JavaItScenario.own(child);
                try {
                    JavaItScenario.stopTree(parent.toHandle());
                    assertThat(child.isAlive()).isFalse();
                } finally {
                    JavaItScenario.stopTree(child);
                }
            } finally {
                JavaItScenario.stopTree(parent.toHandle());
            }
        }
    }

    private void assertParentExitWithOpenStdin() throws Exception {
        try (ServerSocket control = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            control.setSoTimeout(15_000);
            Process parent = startParent("server", workspaceRoot(), control.getLocalPort());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(parent.getInputStream()))) {
                try {
                    ProcessHandle child = readReportedChild(reader);
                    JavaItScenario.own(child);
                    ProcessHandle holder = readReportedChild(reader);
                    JavaItScenario.own(holder);
                    try (Socket input = control.accept()) {
                        assertReadyWithHeldInput(input, parent, child, reader);
                        assertThat(holder.isAlive()).isTrue();
                        parent.getOutputStream().write(1);
                        parent.getOutputStream().flush();
                        assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
                        child.onExit().get(15, TimeUnit.SECONDS);
                        assertThat(child.isAlive()).isFalse();
                        assertThat(holder.isAlive()).as("Stdin writer must outlive the server").isTrue();
                        assertThat(input.isClosed()).isFalse();
                    } finally {
                        JavaItScenario.stopOwnedProcesses(List.of(child, holder));
                    }
                } finally {
                    // Close the pipe before reader.close(), including a timed-out readiness read.
                    JavaItScenario.stopTree(parent.toHandle());
                }
            }
        }
    }

    private static Process startParent(String mode, Path workspace, int port) throws Exception {
        Process parent = new ProcessBuilder(McpServerProcess.javaBinary(), "-cp", testClassesPath().toString(),
                JavaItParentProcess.class.getName(), mode,
                "server".equals(mode) ? jarPath().toString() : testClassesPath().toString(), workspace.toString(),
                Integer.toString(port), testClassesPath().toString()).directory(workspace.toFile()).start();
        JavaItScenario.own(parent);
        return parent;
    }

    private static ProcessHandle readReportedChild(BufferedReader reader) throws Exception {
        var childPid = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return Long.parseLong(reader.readLine());
            } catch (Exception failure) {
                throw new IllegalStateException("Parent helper did not report its child", failure);
            }
        });
        return ProcessHandle.of(childPid.get(10, TimeUnit.SECONDS)).orElseThrow();
    }

    private static void assertReadyWithHeldInput(Socket input, Process parent, ProcessHandle child, BufferedReader reader)
            throws Exception {
        sendThroughHolder(input, request(1, "initialize", Map.of(
                "protocolVersion", McpServerAcceptanceConstants.PROTOCOL_VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "parent-exit-it", "version", "1"))));
        JsonNode initialized = readParentResponse(reader);
        assertThat(initialized.path("id").asInt()).isEqualTo(1);
        assertThat(initialized.path("result").path("protocolVersion").asText())
                .isEqualTo(McpServerAcceptanceConstants.PROTOCOL_VERSION);
        sendThroughHolder(input, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        sendThroughHolder(input, request(2, "resources/read", Map.of("uri", "mcp-java-dev-tools://status")));
        JsonNode response = readParentResponse(reader);
        assertThat(response.path("id").asInt()).isEqualTo(2);
        assertThat(response.has("error")).isFalse();
        JsonNode status = JSON.readTree(response.path("result").path("contents").get(0).path("text").asText());
        assertThat(status.path("ok").asBoolean()).isTrue();
        assertThat(status.path("pid").asLong()).isEqualTo(child.pid());
        assertThat(status.path("ppid").asLong()).isEqualTo(parent.pid());
        assertThat(child.isAlive()).as("Ready server must be alive before parent exit").isTrue();
        assertThat(parent.isAlive()).isTrue();
    }

    private static void sendThroughHolder(Socket input, Map<String, Object> message) throws Exception {
        input.getOutputStream().write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        input.getOutputStream().flush();
    }

    private static JsonNode readParentResponse(BufferedReader reader) throws Exception {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.readTree(reader.readLine());
            } catch (Exception failure) {
                throw new IllegalStateException("Parent helper did not relay an MCP response", failure);
            }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void cleanupContinuesAfterIndividualProcessFailures() throws Exception {
        Process later = new ProcessBuilder(McpServerProcess.javaBinary(), "-cp", testClassesPath().toString(),
                JavaItParentProcess.class.getName(), "sleep").directory(workspaceRoot().toFile()).start();
        JavaItScenario.own(later);
        ProcessHandle broken = (ProcessHandle) java.lang.reflect.Proxy.newProxyInstance(
                ProcessHandle.class.getClassLoader(), new Class<?>[] {ProcessHandle.class},
                (proxy, method, arguments) -> {
                    throw new IllegalStateException("Deliberate process cleanup failure");
                });
        try {
            assertThatThrownBy(() -> JavaItScenario.stopOwnedProcesses(List.of(broken, later.toHandle(), broken)))
                    .isInstanceOf(MultipleFailuresError.class)
                    .satisfies(failure -> assertThat(((MultipleFailuresError) failure).getFailures()).hasSize(2));
            assertThat(later.isAlive()).as("A failed earlier cleanup must not leave later processes alive").isFalse();
        } finally {
            JavaItScenario.stopTree(later.toHandle());
        }
    }

    private static void initializeWithoutRoots(McpServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of("protocolVersion", McpServerAcceptanceConstants.PROTOCOL_VERSION,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "no-roots", "version", "1"))));
        server.responseFor(1);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    @Test
    void configuredWorkspaceWorksWithoutRootsAndRootsRemainAuthoritative() throws Exception {
        Path configured = workspaceRoot();
        Path root = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), root, "--workspace-root=" + configured)) {
            server.send(request(1, "initialize", Map.of("protocolVersion", McpServerAcceptanceConstants.PROTOCOL_VERSION,
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "no-roots", "version", "1"))));
            server.responseFor(1);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            assertThat(status(server, 20).path("workspaceRoot").asText()).isEqualTo(configured.toString());
            assertThat(server.rootsRequests()).isZero();
            upsertProject(server, 21, configured);
        }
        try (McpServerProcess server = McpServerProcess.start(jarPath(), root, "--workspace-root=" + configured)) {
            initialize(server);
            assertThat(status(server, 20).path("workspaceRoot").asText()).isEqualTo(root.toString());
        }
    }

    @Test
    void cleanupSurvivesFailedSetupAssertionAndRequestTimeout() throws Exception {
        Path unrelated = workspaceRoot().resolve("unrelated.txt");
        Files.writeString(unrelated, "untouched");
        try (McpServerProcess other = McpServerProcess.start(jarPath(), workspaceRoot())) {
            initialize(other);
            for (String mode : List.of("setup", "assertion", "timeout")) {
                McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot());
                assertThatThrownBy(() -> {
                    try (server) {
                        if ("setup".equals(mode)) {
                            throw new AssertionError("Deliberate partial setup failure");
                        }
                        initialize(server);
                        if ("timeout".equals(mode)) {
                            server.responseFor(999, Duration.ofMillis(100));
                        }
                        throw new AssertionError("Deliberate assertion failure");
                    }
                }).isInstanceOf(AssertionError.class);
                assertThat(server.isAlive()).isFalse();
                assertThat(other.isAlive()).isTrue();
                assertThat(Files.readString(unrelated)).isEqualTo("untouched");
            }
        }
    }

    private static JsonNode call(McpServerProcess server, int id, String name, Map<String, Object> arguments)
            throws Exception {
        server.send(request(id, "tools/call", Map.of("name", name, "arguments", arguments)));
        return toolPayload(server.responseFor(id));
    }

    private static JsonNode status(McpServerProcess server, int id) throws Exception {
        server.send(request(id, "resources/read", Map.of("uri", "mcp-java-dev-tools://status")));
        JsonNode response = server.responseFor(id);
        assertThat(response.has("error")).as(response.toString()).isFalse();
        return JSON.readTree(response.path("result").path("contents").get(0).path("text").asText());
    }

    private static void assertSafeStatus(JsonNode status, McpServerProcess server, Path workspace) {
        assertThat(status.path("ok").asBoolean()).isTrue();
        assertThat(status.path("pid").asLong()).isEqualTo(server.pid());
        assertThat(status.path("ppid").asLong()).isEqualTo(ProcessHandle.current().pid());
        assertThat(status.path("version").asText()).isNotBlank();
        assertThat(status.path("buildFingerprint").asText()).startsWith("implementation-version:");
        assertThat(Instant.parse(status.path("time").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(status.path("workspaceRoot").asText()).isEqualTo(workspace.toString());
        assertThat(status.fieldNames()).toIterable().containsExactlyInAnyOrder("ok", "name", "version",
                "buildFingerprint", "pid", "ppid", "workspaceRoot", "workspaceRootSource",
                "workspaceReasonCode", "rootsDiscoveryStatus", "javaVersion", "time");
    }

    private static void upsertProject(McpServerProcess server, int id, Path workspace) throws Exception {
        Map<String, Object> arguments = Map.of("projectName", "lifecycle",
                "payload", Map.of("workspaces", List.of(Map.of("projectRoot", workspace.toString(),
                        "defaults", Map.of("orchestrator", Map.of("resumePollMax", 1,
                                "resumePollIntervalMs", 10, "resumePollTimeoutMs", 100))))));
        server.send(executeRequest(id, "artifact_management.project_context.upsert", arguments, false));
        assertThat(toolPayload(server.responseFor(id)).path("status").asText()).isEqualTo("confirmation_required");
        assertThat(workspace.resolve(".mcpjvm/lifecycle/projects.json")).doesNotExist();
        server.send(executeRequest(id + 1000, "artifact_management.project_context.upsert", arguments, true));
        JsonNode result = toolPayload(server.responseFor(id + 1000));
        assertThat(result.path("status").asText()).isEqualTo("succeeded");
        assertThat(result.path("result").path("status").asText()).as(result.toString()).isEqualTo("ok");
    }
}
