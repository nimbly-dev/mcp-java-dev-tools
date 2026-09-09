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

/** Raw-STDIO compatibility evidence for MCPJVM-618's twelve plan invocations. */
class ArtifactPlanLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final Set<String> OWNED = Set.of(
            "performance_plan/read", "performance_plan/validate",
            "performance_plan/upsert", "performance_plan/list",
            "regression_plan/read", "regression_plan/validate",
            "regression_plan/upsert", "regression_plan/list",
            "security_plan/read", "security_plan/validate",
            "security_plan/upsert", "security_plan/list");

    @TempDir
    Path workspace;

    @Test
    void packagedServerPreservesAllTwelvePlanRowsOverProtocolOnlyStdout() throws Exception {
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            server.send(request(1, "initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("roots", Map.of("listChanged", true)),
                    "clientInfo", Map.of("name", "mcpjvm-618-it", "version", "1.0"))));
            assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                    "params", Map.of()));
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                    "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            assertInventory(server.responseFor(2));

            int id = 3;
            for (String suite : List.of("performance", "regression", "security")) {
                String type = suite + "_plan";
                Map<String, Object> selector = Map.of(
                        "projectName", "demo", "planName", suite + "-plan");
                assertOk(server.call(id++, type, "upsert", Map.of(
                        "projectName", "demo", "planName", suite + "-plan",
                        "payload", payload(suite))), type, "upsert");
                assertOk(server.call(id++, type, "read", selector), type, "read");
                assertOk(server.call(id++, type, "validate", selector), type, "validate");
                JsonNode listed = server.call(id++, type, "list", Map.of("projectName", "demo"));
                assertOk(listed, type, "list");
                assertThat(listed.path("planNames").get(0).asText()).isEqualTo(suite + "-plan");
            }
        }
    }

    @Test
    void regressionReadMatchesReleasedTypeScriptDefaultAndSelectedOutputs() throws Exception {
        Path plan = workspace.resolve(".mcpjvm/demo/plans/regression/regression-plan");
        Files.createDirectories(plan);
        JSON.writerWithDefaultPrettyPrinter().writeValue(plan.resolve("metadata.json").toFile(),
                Map.of("execution", Map.of("intent", "regression")));
        JSON.writerWithDefaultPrettyPrinter().writeValue(plan.resolve("contract.json").toFile(),
                Map.of(
                        "targets", List.of(Map.of("id", "target")),
                        "prerequisites", List.of(Map.of("id", "first"), Map.of("id", "second")),
                        "steps", List.of(
                                Map.of("id", "first", "protocol", "http"),
                                Map.of("id", "second", "protocol", "http"))));
        Files.writeString(plan.resolve("plan.md"), "# regression plan\n");

        JsonNode javaDefault;
        JsonNode javaSelected;
        String javaFractionalOffset;
        String javaFractionalLimit;
        try (ServerProcess server = ServerProcess.start(jarPath(), workspace)) {
            initialize(server);
            javaDefault = server.call(3, "regression_plan", "read", Map.of(
                    "projectName", "demo", "planName", "regression-plan"));
            javaSelected = server.call(4, "regression_plan", "read", Map.of(
                    "projectName", "demo", "planName", "regression-plan",
                    "query", Map.of(
                            "select", List.of("summary", "targets", "prerequisites", "steps",
                                    "metadata", "contract", "plan"),
                            "prerequisites", Map.of("offset", 1, "limit", 1),
                            "steps", Map.of("offset", 1, "limit", 1))));
            javaFractionalOffset = server.callFailure(5, "regression_plan", "read", Map.of(
                    "projectName", "demo", "planName", "regression-plan",
                    "query", Map.of("select", List.of("steps"),
                            "steps", Map.of("offset", 0.5, "limit", 1))));
            javaFractionalLimit = server.callFailure(6, "regression_plan", "read", Map.of(
                    "projectName", "demo", "planName", "regression-plan",
                    "query", Map.of("select", List.of("steps"),
                            "steps", Map.of("offset", 0, "limit", 1.5))));
        }
        JsonNode typescript = runTypeScriptRegressionReads(workspace);
        JsonNode typescriptDefault = typescript.path("defaultRead");
        JsonNode typescriptSelected = typescript.path("selectedRead");

        assertThat(compatibilityView(javaDefault)).isEqualTo(typescriptDefault);
        assertThat(compatibilityView(javaSelected)).isEqualTo(typescriptSelected);
        assertThat(javaDefault.has("artifact")).isFalse();
        assertThat(javaDefault.path("summary")).isEqualTo(JSON.valueToTree(Map.of(
                "intent", "regression", "stepCount", 2,
                "targetCount", 1, "prerequisiteCount", 2)));
        assertThat(javaSelected.path("steps")).isEqualTo(JSON.valueToTree(Map.of(
                "offset", 1, "limit", 1, "returned", 1, "total", 2,
                "items", List.of(Map.of("id", "second", "protocol", "http")))));
        assertThat(javaSelected.path("artifact").path("metadata").path("execution")
                .path("intent").asText()).isEqualTo("regression");
        assertThat(javaSelected.path("artifact").path("contract").path("steps")).hasSize(2);
        assertThat(javaSelected.path("artifact").path("plan").asText())
                .isEqualTo("# regression plan\n");
        assertThat(typescript.path("fractionalOffsetError").asText())
                .isEqualTo("window_query_required");
        assertThat(typescript.path("fractionalLimitError").asText())
                .isEqualTo("window_query_required");
        assertThat(javaFractionalOffset)
                .contains("/input/query/steps/offset: number found, integer expected");
        assertThat(javaFractionalLimit)
                .contains("/input/query/steps/limit: number found, integer expected");
        writeCompatibilityEvidence(
                typescriptDefault, compatibilityView(javaDefault),
                typescriptSelected, compatibilityView(javaSelected));
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-618-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed",
                "params", Map.of()));
    }

    private static JsonNode runTypeScriptRegressionReads(Path root) throws Exception {
        Path repository = repositoryRoot();
        Path runner = root.resolve("mcpjvm-618-typescript-comparison.cjs");
        Files.writeString(runner, """
                const path = require("node:path");
                const repository = process.argv[3];
                const workspaceRootAbs = process.argv[2];
                const { handleRegressionPlanArtifact } = require(path.join(repository,
                  "tools/features/artifact-management/actions/regression_plan.action.ts"));
                const base = { artifactType: "regression_plan", action: "read",
                  input: { projectName: "demo", planName: "regression-plan" } };
                (async () => {
                  const defaultRead = await handleRegressionPlanArtifact({ workspaceRootAbs }, base);
                  const selectedRead = await handleRegressionPlanArtifact({ workspaceRootAbs }, {
                    ...base,
                    input: { ...base.input, query: {
                      select: ["summary", "targets", "prerequisites", "steps",
                        "metadata", "contract", "plan"],
                      prerequisites: { offset: 1, limit: 1 },
                      steps: { offset: 1, limit: 1 }
                    } }
                  });
                  const failure = async (steps) => {
                    try {
                      await handleRegressionPlanArtifact({ workspaceRootAbs }, {
                        ...base, input: { ...base.input, query: { select: ["steps"], steps } }
                      });
                      return null;
                    } catch (error) {
                      return error instanceof Error ? error.message : String(error);
                    }
                  };
                  process.stdout.write(JSON.stringify({
                    defaultRead: defaultRead.structuredContent,
                    selectedRead: selectedRead.structuredContent,
                    fractionalOffsetError: await failure({ offset: 0.5, limit: 1 }),
                    fractionalLimitError: await failure({ offset: 0, limit: 1.5 })
                  }));
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(
                "node", "--require", "ts-node/register", "--require", "tsconfig-paths/register",
                runner.toString(), root.toString(), repository.toString())
                .directory(repository.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return JSON.readTree(output.trim());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("tools/contracts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current;
    }

    private static JsonNode compatibilityView(JsonNode javaPayload) {
        ObjectNode output = javaPayload.deepCopy();
        output.remove(List.of(
                "reasonCode", "nextActionCode", "nextAction", "reason", "reasonMeta", "details"));
        return output;
    }

    private static void writeCompatibilityEvidence(
            JsonNode typescriptDefault,
            JsonNode javaDefault,
            JsonNode typescriptSelected,
            JsonNode javaSelected) throws IOException {
        Path evidence = Path.of("target", "mcpjvm-618-evidence");
        Files.createDirectories(evidence);
        Files.deleteIfExists(evidence.resolve("compatibility-discrepancy.json"));
        ObjectNode parity = JSON.createObjectNode();
        parity.put("ticket", "MCPJVM-618");
        parity.put("status", "parity_verified");
        parity.put("scope", "regression_plan/read default and complete selected projection");
        parity.set("typescriptDefault", typescriptDefault);
        parity.set("javaDefault", javaDefault);
        parity.set("typescriptSelected", typescriptSelected);
        parity.set("javaSelected", javaSelected);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("compatibility-parity.json").toFile(), parity);
    }

    private static Map<String, Object> payload(String suite) {
        if ("performance".equals(suite)) {
            return Map.of(
                    "metadata", Map.of("suiteType", "performance",
                            "execution", Map.of("intent", "performance")),
                    "contract", Map.of(
                            "suiteType", "performance",
                            "loadModel", Map.of("mode", "concurrency",
                                    "concurrency", 1, "durationSeconds", 1),
                            "successCriteria", Map.of(),
                            "workloadProvider", Map.of("type", "jmeter"),
                            "entrypoints", List.of(Map.of("id", "health"))),
                    "plan", "# performance plan\n");
        }
        if ("regression".equals(suite)) {
            return Map.of("metadata", Map.of(), "contract", Map.of(
                    "steps", List.of(Map.of("id", "health", "protocol", "http"))));
        }
        return Map.of("metadata", Map.of(), "contract", Map.of("suiteType", "security"));
    }

    private static void assertInventory(JsonNode response) {
        JsonNode artifact = null;
        for (JsonNode tool : response.path("result").path("tools")) {
            if ("artifact_management".equals(tool.path("name").asText())) {
                artifact = tool;
            }
        }
        assertThat(artifact).isNotNull();
        java.util.Set<String> observed = new java.util.LinkedHashSet<>();
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
        assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo("ok");
        assertThat(payload.path("reasonCode").asText()).isEqualTo("success");
        assertThat(payload.path("artifactType").asText()).isEqualTo(type);
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

        JsonNode call(int id, String type, String action, Map<String, Object> input) throws Exception {
            send(request(id, "tools/call", Map.of("name", "artifact_management",
                    "arguments", Map.of("artifactType", type, "action", action, "input", input))));
            JsonNode response = responseFor(id);
            return JSON.readTree(response.path("result").path("content").get(0).path("text").asText());
        }

        String callFailure(int id, String type, String action, Map<String, Object> input)
                throws Exception {
            send(request(id, "tools/call", Map.of("name", "artifact_management",
                    "arguments", Map.of("artifactType", type, "action", action, "input", input))));
            JsonNode response = responseFor(id);
            assertThat(response.path("result").path("isError").asBoolean()).isTrue();
            return response.path("result").path("content").get(0).path("text").asText();
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

        private List<String> stdoutLines() {
            synchronized (stdout) {
                return List.copyOf(stdout);
            }
        }

        private void respondToRoots(JsonNode request) throws IOException {
            com.fasterxml.jackson.databind.node.ObjectNode response = JSON.createObjectNode()
                    .put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result").putArray("roots").addObject()
                    .put("uri", workspaceUri).put("name", "mcpjvm-618-workspace");
            send(JSON.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { }));
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
            assertThat(stdoutLines()).isNotEmpty().allSatisfy(line -> {
                try {
                    assertThat(JSON.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
                } catch (IOException exception) {
                    throw new AssertionError("stdout contained a non-JSON-RPC line", exception);
                }
            });
        }
    }
}
