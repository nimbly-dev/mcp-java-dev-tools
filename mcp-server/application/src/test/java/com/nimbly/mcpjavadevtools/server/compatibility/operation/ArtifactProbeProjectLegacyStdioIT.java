package com.nimbly.mcpjavadevtools.server.compatibility.operation;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

/** Raw-STDIO compatibility evidence for MCPJVM-617's eight released invocations. */
class ArtifactProbeProjectLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final Set<String> OWNED_ROUTES = Set.of(
            "probe_config/read", "probe_config/validate", "probe_config/upsert",
            "probe_config/reload", "project_context/read", "project_context/validate",
            "project_context/upsert", "project_context/list");

    @TempDir
    Path workspace;

    @Test
    void packagedServerPreservesAllEightLegacyRowsOverProtocolOnlyStdout() throws Exception {
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            server.send(request(1, "initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("roots", Map.of("listChanged", true)),
                    "clientInfo", Map.of("name", "mcpjvm-617-it", "version", "1.0"))));
            assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                    "params", Map.of()));
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                    "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            assertOwnedInventory(server.responseFor(2));

            JsonNode missing = server.call(3, "probe_config", "read", Map.of());
            assertPayload(missing).containsEntry("status", "not_configured")
                    .containsEntry("reasonCode", "probe_registry_not_configured");
            assertThat(missing.path("nextActionCode").asText()).isEqualTo("set_probe_registry_config");
            Map<String, Object> probe = Map.of(
                    "defaultProfile", "local",
                    "profiles", Map.of("local", Map.of(
                            "probes", Map.of("local", Map.of(
                                    "baseUrl", "http://127.0.0.1:9191",
                                    "include", List.of("com.example"),
                                    "exclude", List.of("com.example.generated"))))));
            JsonNode probeUpsert = server.call(4, "probe_config", "upsert", Map.of("payload", probe));
            assertOk(probeUpsert, "probe_config", "upsert");
            assertThat(probeUpsert.path("reloadApplied").asBoolean()).isTrue();
            assertThat(probeUpsert.path("activeProbeCount").asInt()).isEqualTo(1);
            JsonNode probeRead = server.call(5, "probe_config", "read", Map.of());
            assertOk(probeRead, "probe_config", "read");
            assertThat(probeRead.path("artifact")).isEqualTo(JSON.valueToTree(probe));
            assertThat(probeRead.path("activeProfile").asText()).isEqualTo("local");
            assertThat(probeRead.path("profileSource").asText()).isEqualTo("default");
            assertThat(probeRead.path("probeCount").asInt()).isEqualTo(1);
            JsonNode probeValidate = server.call(6, "probe_config", "validate", Map.of());
            assertOk(probeValidate, "probe_config", "validate");
            assertThat(probeValidate.path("probeCount").asInt()).isEqualTo(1);
            JsonNode probeReload = server.call(7, "probe_config", "reload", Map.of());
            assertCapability(probeReload, "probe_config", "reload", "reloaded");
            assertThat(probeReload.path("reloadApplied").asBoolean()).isTrue();
            assertThat(probeReload.path("activeProbeCount").asInt()).isEqualTo(1);

            Map<String, Object> project = projectArtifact(workspace);
            Path zetaRoot = Files.createDirectory(workspace.resolve("zeta-root"));
            Path alphaRoot = Files.createDirectory(workspace.resolve("alpha-root"));
            Map<String, Path> projectRoots = Map.of(
                    "sample", workspace, "zeta", zetaRoot, "alpha", alphaRoot);
            int id = 8;
            for (String projectName : List.of("sample", "zeta", "alpha")) {
                Path projectPath = workspace.resolve(".mcpjvm/" + projectName + "/projects.json");
                assertThat(Files.notExists(projectPath)).as(projectPath.toString()).isTrue();
                JsonNode upsert = server.call(id++, "project_context", "upsert", Map.of(
                        "projectName", projectName,
                        "payload", projectArtifact(projectRoots.get(projectName))));
                assertOk(upsert, "project_context", "upsert");
                assertThat(Files.isRegularFile(projectPath)).as(projectPath.toString()).isTrue();
                assertThat(upsert.path("projectName").asText()).isEqualTo(projectName);
                assertThat(upsert.path("updateMode").asText()).as(projectName + ": " + upsert)
                        .isEqualTo("created");
                assertThat(upsert.path("stateStore").path("provisioned").asBoolean()).isTrue();
                assertThat(Files.isRegularFile(workspace.resolve(
                        ".mcpjvm/" + projectName + "/run-state.sqlite"))).isTrue();
            }
            JsonNode projectRead = server.call(id++, "project_context", "read", Map.of(
                    "projectName", "sample", "query", Map.of("select", List.of("artifact"))));
            assertOk(projectRead, "project_context", "read");
            assertThat(projectRead.path("artifact")).isEqualTo(JSON.valueToTree(project));
            JsonNode rootSelected = server.call(id++, "project_context", "read", Map.of(
                    "projectRootAbs", zetaRoot.toString(),
                    "query", Map.of("select", List.of("artifact"))));
            assertOk(rootSelected, "project_context", "read");
            assertThat(rootSelected.path("projectName").asText()).isEqualTo("zeta");
            JsonNode projectValidate = server.call(id++, "project_context", "validate", Map.of(
                    "projectName", "sample", "projectRootAbs", workspace.toString()));
            assertOk(projectValidate, "project_context", "validate");
            assertThat(projectValidate.path("valid").asBoolean()).isTrue();
            assertThat(projectValidate.path("workspaceCount").asInt()).isEqualTo(1);
            JsonNode listed = server.call(id, "project_context", "list", Map.of());
            assertOk(listed, "project_context", "list");
            assertThat(listed.path("projectNames")).containsExactly(
                    JSON.getNodeFactory().textNode("alpha"),
                    JSON.getNodeFactory().textNode("sample"),
                    JSON.getNodeFactory().textNode("zeta"));

            assertThat(Files.isRegularFile(workspace.resolve(".mcpjvm/probe-config.json"))).isTrue();
            assertThat(Files.isRegularFile(workspace.resolve(".mcpjvm/sample/projects.json"))).isTrue();
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile()))
                    .isEqualTo(probeRead.path("artifact"));
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/sample/projects.json").toFile()))
                    .isEqualTo(JSON.valueToTree(project));
            ObjectNode redactedPayload = JSON.valueToTree(probe);
            redactedPayload.put("authorization", "Bearer mcpjvm-617-secret");
            JsonNode redacted = server.call(id + 1, "probe_config", "upsert", Map.of(
                    "payload", redactedPayload));
            assertOk(redacted, "probe_config", "upsert");
            assertThat(redacted.toString()).doesNotContain("mcpjvm-617-secret");
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile())
                    .path("authorization").asText()).isEqualTo("[REDACTED]");
            Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), "null\n");
            JsonNode nullArtifact = server.call(id + 2, "probe_config", "read", Map.of());
            assertOk(nullArtifact, "probe_config", "read");
            assertThat(nullArtifact.has("artifact")).isFalse();
            assertThat(nullArtifact.path("activeProfile").asText()).isEqualTo("local");
            assertThat(nullArtifact.path("probeCount").asInt()).isEqualTo(1);
            Map<String, Object> invalidRuntime = Map.of("workspaces", List.of(Map.of(
                    "projectRoot", workspace.toString(),
                    "runtimeContexts", List.of(Map.of("name", "bad", "mode", "invalid")),
                    "defaults", Map.of("orchestrator", Map.of(
                            "resumePollMax", 1,
                            "resumePollIntervalMs", 10,
                            "resumePollTimeoutMs", 100)))));
            JsonNode rejectedRuntime = server.call(id + 3, "project_context", "upsert", Map.of(
                    "projectName", "invalid-runtime", "payload", invalidRuntime));
            assertPayload(rejectedRuntime)
                    .containsEntry("status", "runtime_context_unknown")
                    .containsEntry("reasonCode", "runtime_context_unknown");
            assertThat(Files.exists(workspace.resolve(
                    ".mcpjvm/invalid-runtime/projects.json"))).isFalse();
            assertThat(server.stdoutLines()).isNotEmpty().allSatisfy(line -> {
                try {
                    assertThat(JSON.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
                } catch (IOException exception) {
                    throw new AssertionError("stdout contained a non-JSON-RPC line: " + line, exception);
                }
            });
        }
    }

    private static Map<String, Object> projectArtifact(Path projectRoot) {
        return Map.of("workspaces", List.of(Map.of(
                "projectRoot", projectRoot.toString(), "defaults", Map.of(
                        "orchestrator", Map.of(
                                "resumePollMax", 1,
                                "resumePollIntervalMs", 10,
                                "resumePollTimeoutMs", 100)))));
    }

    private static void assertOwnedInventory(JsonNode response) {
        JsonNode tools = response.path("result").path("tools");
        JsonNode artifact = null;
        for (JsonNode tool : tools) {
            if ("artifact_management".equals(tool.path("name").asText())) {
                artifact = tool;
                break;
            }
        }
        assertThat(artifact).isNotNull();
        Set<String> observed = new java.util.LinkedHashSet<>();
        for (JsonNode branch : artifact.path("inputSchema").path("oneOf")) {
            String type = branch.path("properties").path("artifactType").path("const").asText();
            String action = branch.path("properties").path("action").path("const").asText();
            String route = type + "/" + action;
            if (OWNED_ROUTES.contains(route)) {
                observed.add(route);
                assertPublishedBranch(route, branch);
            }
        }
        assertThat(observed).containsExactlyInAnyOrderElementsOf(OWNED_ROUTES);
    }

    private static void assertPublishedBranch(String route, JsonNode branch) {
        assertThat(branch.path("type").asText()).isEqualTo("object");
        assertThat(branch.path("additionalProperties").asBoolean()).isFalse();
        assertThat(textValues(branch.path("required")))
                .containsExactlyInAnyOrder("artifactType", "action", "input");
        JsonNode input = branch.path("properties").path("input");
        assertThat(input.path("type").asText()).isEqualTo("object");
        assertThat(input.path("additionalProperties").asBoolean()).isFalse();
        Set<String> expected = route.startsWith("probe_config/")
                ? Set.of("payload")
                : Set.of("projectName", "projectRootAbs", "payload", "replace", "query");
        assertThat(fieldNames(input.path("properties")))
                .as(route).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(textValues(input.path("required"))).as(route).isEmpty();
        if (route.startsWith("probe_config/")) {
            assertThat(input.path("properties").path("payload").path("type").asText())
                    .isEqualTo("object");
        } else {
            assertThat(input.path("properties").path("projectName").path("type").asText())
                    .isEqualTo("string");
            assertThat(input.path("properties").path("projectRootAbs").path("type").asText())
                    .isEqualTo("string");
            assertThat(input.path("properties").path("payload").path("type").asText())
                    .isEqualTo("object");
            assertThat(input.path("properties").path("replace").path("type").asText())
                    .isEqualTo("boolean");
            assertThat(input.path("properties").path("replace").path("default").asBoolean())
                    .isFalse();
            assertThat(input.path("properties").path("query").path("type").asText())
                    .isEqualTo("object");
        }
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> values = new java.util.LinkedHashSet<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static Set<String> textValues(JsonNode array) {
        Set<String> values = new java.util.LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static org.assertj.core.api.MapAssert<String, String> assertPayload(JsonNode payload) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("status", payload.path("status").asText());
        values.put("reasonCode", payload.path("reasonCode").asText());
        return assertThat(values);
    }

    private static void assertOk(JsonNode payload, String artifactType, String action) {
        assertCapability(payload, artifactType, action, "ok");
    }

    private static void assertCapability(
            JsonNode payload, String artifactType, String action, String status) {
        assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo(status);
        assertThat(payload.path("reasonCode").asText()).isEqualTo("success");
        assertThat(payload.path("artifactType").asText()).isEqualTo(artifactType);
        assertThat(payload.path("action").asText()).isEqualTo(action);
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

        JsonNode call(int id, String artifactType, String action, Map<String, Object> input)
                throws Exception {
            send(request(id, "tools/call", Map.of(
                    "name", "artifact_management",
                    "arguments", Map.of(
                            "artifactType", artifactType, "action", action, "input", input))));
            JsonNode response = responseFor(id);
            String text = response.path("result").path("content").get(0).path("text").asText();
            return JSON.readTree(text);
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

        List<String> stdoutLines() {
            synchronized (stdout) {
                return List.copyOf(stdout);
            }
        }

        private void respondToRoots(JsonNode request) throws IOException {
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result").putArray("roots").addObject()
                    .put("uri", workspaceUri).put("name", "mcpjvm-617-workspace");
            send(response);
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
        }
    }
}
