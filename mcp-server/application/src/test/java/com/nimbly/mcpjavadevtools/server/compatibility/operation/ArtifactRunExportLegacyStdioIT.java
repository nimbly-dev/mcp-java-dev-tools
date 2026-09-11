package com.nimbly.mcpjavadevtools.server.compatibility.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
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

/** Raw-STDIO compatibility evidence for all eleven run-result and execution-export rows. */
class ArtifactRunExportLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> OWNED = Set.of(
            "run_result/read", "run_result/upsert", "run_result/list",
            "run_result/rebuild", "run_result/backfill", "run_result/cutover",
            "run_result/query", "run_result/cleanup",
            "execution_export/read", "execution_export/list", "execution_export/generate");

    @TempDir
    Path workspace;

    @Test
    void packagedServerExercisesAllElevenCompatibleRows() throws Exception {
        seedArtifacts();
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            server.send(request(2, "tools/list", Map.of()));
            assertInventory(server.responseFor(2));

            int id = 3;
            JsonNode upserted = server.call(id++, "run_result", "upsert", runUpsert());
            assertSuccessful(upserted, "persisted", "run_result", "upsert");
            assertThat(upserted.path("path").asText()).endsWith("execution.result.json");
            JsonNode run = server.call(id++, "run_result", "read", runSelector());
            assertOk(run, "run_result", "read");
            assertThat(run.path("artifact").path("status").asText())
                    .as(run.toString()).isEqualTo("updated");
            assertOk(server.call(id++, "run_result", "list", Map.of(
                    "projectName", "demo", "suiteType", "regression", "planName", "health")),
                    "run_result", "list");
            assertOk(server.call(id++, "run_result", "rebuild", project()),
                    "run_result", "rebuild");
            assertOk(server.call(id++, "run_result", "backfill", Map.of(
                    "projectName", "demo", "stateSurface", "correlation_state")),
                    "run_result", "backfill");
            assertOk(server.call(id++, "run_result", "cutover", project()),
                    "run_result", "cutover");
            assertOk(server.call(id++, "run_result", "query", project()),
                    "run_result", "query");
            assertOk(server.call(id++, "run_result", "cleanup", project()),
                    "run_result", "cleanup");

            JsonNode generated = server.call(id++, "execution_export", "generate", exportInput());
            assertOk(generated, "execution_export", "generate");
            String exportId = generated.path("exportId").asText();
            assertThat(exportId).isNotBlank();
            JsonNode listed = server.call(id++, "execution_export", "list", project());
            assertOk(listed, "execution_export", "list");
            assertThat(listed.path("exportFolders")).contains(JSON.getNodeFactory().textNode(exportId));
            JsonNode read = server.call(id, "execution_export", "read", Map.of(
                    "projectName", "demo", "query", Map.of("exportId", exportId)));
            assertOk(read, "execution_export", "read");
            assertThat(read.path("exportId").asText()).isEqualTo(exportId);
        }
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-619-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                "params", Map.of()));
    }

    private static void assertInventory(JsonNode response) {
        JsonNode artifact = null;
        for (JsonNode tool : response.path("result").path("tools")) {
            if ("artifact_management".equals(tool.path("name").asText())) {
                artifact = tool;
            }
        }
        assertThat(artifact).isNotNull();
        Set<String> observed = new LinkedHashSet<>();
        for (JsonNode branch : artifact.path("inputSchema").path("oneOf")) {
            String route = branch.path("properties").path("artifactType").path("const").asText()
                    + "/" + branch.path("properties").path("action").path("const").asText();
            if (OWNED.contains(route)) {
                observed.add(route);
                assertThat(branch.path("additionalProperties").asBoolean()).isFalse();
            }
        }
        assertThat(observed).containsExactlyInAnyOrderElementsOf(OWNED);
    }

    private static void assertOk(JsonNode payload, String type, String action) {
        assertSuccessful(payload, "ok", type, action);
    }

    private static void assertSuccessful(JsonNode payload, String status, String type, String action) {
        assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo(status);
        assertThat(payload.path("reasonCode").asText()).isEqualTo("success");
        assertThat(payload.path("artifactType").asText()).isEqualTo(type);
        assertThat(payload.path("action").asText()).isEqualTo(action);
    }

    private void seedArtifacts() throws Exception {
        Path project = workspace.resolve(".mcpjvm/demo");
        Files.createDirectories(project.resolve("plans/regression/health/runs/run-1"));
        ObjectNode projects = JSON.createObjectNode();
        ObjectNode selected = projects.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString());
        selected.putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke").put("suiteType", "regression")
                .put("executionPolicy", "stop_on_fail").putArray("plans").addObject()
                .put("order", 1).put("planName", "health").put("onFail", "inherit");
        writeJson(project.resolve("projects.json"), projects);
        writeJson(project.resolve("plans/regression/health/metadata.json"), JSON.createObjectNode());
        ObjectNode contract = JSON.createObjectNode();
        contract.putArray("targets").addObject();
        contract.putArray("steps").addObject().put("id", "health").put("protocol", "http")
                .putObject("transport").putObject("http").put("method", "GET")
                .put("url", "http://127.0.0.1:9196/health");
        writeJson(project.resolve("plans/regression/health/contract.json"), contract);
        writeJson(project.resolve("plans/regression/health/runs/run-1/execution.result.json"),
                JSON.createObjectNode().put("status", "pass"));
    }

    private static void writeJson(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static Map<String, Object> runSelector() {
        return Map.of("projectName", "demo", "suiteType", "regression",
                "planName", "health", "runId", "run-1");
    }

    private static Map<String, Object> runUpsert() {
        Map<String, Object> input = new java.util.LinkedHashMap<>(runSelector());
        input.put("payload", Map.of("status", "updated"));
        return input;
    }

    private static Map<String, Object> exportInput() {
        return Map.of("projectName", "demo", "mode", "sh",
                "planName", "health", "executionProfile", "smoke");
    }

    private static Map<String, Object> project() {
        return Map.of("projectName", "demo");
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

        JsonNode call(int id, String type, String action, Map<String, Object> input)
                throws Exception {
            send(request(id, "tools/call", Map.of("name", "artifact_management",
                    "arguments", Map.of("artifactType", type, "action", action, "input", input))));
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
                    .put("uri", workspaceUri).put("name", "mcpjvm-619-workspace");
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
            assertProtocolOnlyStdout();
        }

        private void assertProtocolOnlyStdout() {
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
