package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owned post-app and packaged CDE server for the JVM/Probe capability scenarios. */
class JvmProbeScenario extends McpServerStdioSupport implements AutoCloseable {
    static final String CONTROLLER = "com.example.social.post.app.controller.PostController";
    static final String SERVICE = "com.example.social.post.app.service.PostService";
    static final String SOURCE = "test/fixtures/spring-apps/social-platform/post-service/post-app/src/main/java/";
    final Path workspace;
    final SpringFixtureTargetProcess target;
    final McpServerProcess server;
    final int probePort;
    final String baseUrl;
    private int nextId = 100;

    private JvmProbeScenario(Path workspace, SpringFixtureTargetProcess target, McpServerProcess server,
            int probePort) {
        this.workspace = workspace;
        this.target = target;
        this.server = server;
        this.probePort = probePort;
        this.baseUrl = "http://127.0.0.1:" + probePort;
    }

    static JvmProbeScenario start(String... javaArguments) throws Exception {
        Path fixture = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/post-service/"
                + "post-app/target/post-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixture).as("Package post-app using java-it-jvm-fixtures").isRegularFile();
        Path workspace = JavaItScenario.workspace();
        Path temporary = Files.createDirectories(workspace.resolve("tmp"));
        List<String> fixtureArguments = new java.util.ArrayList<>(List.of(javaArguments));
        fixtureArguments.add("-Djava.io.tmpdir=" + temporary);
        SpringFixtureTargetProcess target = SpringFixtureTargetProcess.start(
                fixture, fixtureArguments, List.of());
        try {
            target.awaitHealthy();
            McpServerProcess server = startServer(workspace);
            try {
                initialize(server);
                return new JvmProbeScenario(workspace, target, server, freePort());
            } catch (Throwable failure) {
                server.close();
                throw failure;
            }
        } catch (Throwable failure) {
            target.close();
            throw failure;
        }
    }

    static McpServerProcess startServer(Path workspace, String... applicationArguments) throws Exception {
        // The Sidecar validates lifecycle reports against its own temp directory. The helper and
        // fixture must therefore share this scenario-owned directory (also for JFR repositories).
        Path temporary = Files.createDirectories(workspace.resolve("tmp"));
        List<String> arguments = new java.util.ArrayList<>(List.of("--workspace-root=" + workspace));
        arguments.addAll(List.of(applicationArguments));
        return McpServerProcess.startWithEnvironment(jarPath(), workspace,
                Map.of("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=\"" + temporary + "\""),
                arguments.toArray(String[]::new));
    }

    JsonNode execute(String operation, Map<String, Object> arguments, boolean confirmed) throws Exception {
        int id = nextId++;
        server.send(executeRequest(id, operation, arguments, confirmed));
        JsonNode payload = toolPayload(server.responseFor(id, Duration.ofSeconds(90)));
        JavaItScenario.observe(operation, payload.path("status").asText(),
                payload.path("result").path("reasonCode").asText());
        return payload;
    }

    JsonNode result(String operation, Map<String, Object> arguments, boolean confirmed) throws Exception {
        JsonNode payload = execute(operation, arguments, confirmed);
        assertThat(payload.path("status").asText()).as(payload.toPrettyString()).isEqualTo("succeeded");
        return payload.path("result");
    }

    JsonNode probe(String operation, Map<String, Object> arguments) throws Exception {
        Map<String, Object> routed = new LinkedHashMap<>(arguments);
        routed.putIfAbsent("baseUrl", baseUrl);
        return result("probe." + operation, routed, true);
    }

    JsonNode success(String operation, Map<String, Object> arguments) throws Exception {
        JsonNode result = probe(operation, arguments);
        assertThat(result.path("reasonCode").asText()).as(result.toPrettyString()).isEqualTo("SUCCESS");
        return result.path("actionResult");
    }

    Map<String, Object> identity() {
        return Map.of("pid", Long.toString(target.pid()),
                "expectedProcessStartEpochMs", target.processStartEpochMs());
    }

    Map<String, Object> attachArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>(identity());
        arguments.put("probePort", probePort);
        arguments.put("include", "com.example.social.**");
        return arguments;
    }

    void attach() throws Exception {
        JsonNode result = result("jvm_lifecycle.attach", attachArguments(), true);
        assertThat(result.path("reasonCode").asText()).as(result.toPrettyString()).isEqualTo("active");
        assertThat(target.awaitSidecarReachability(probePort, true, Duration.ofSeconds(10))).isTrue();
    }

    void deactivate() throws Exception {
        JsonNode result = result("jvm_lifecycle.deactivate", identity(), true);
        assertThat(result.path("reasonCode").asText()).as(result.toPrettyString()).isEqualTo("deactivated");
        assertThat(result.path("actionResult").path("nonRestorableClasses")).isEmpty();
        assertThat(target.awaitSidecarReachability(probePort, false, Duration.ofSeconds(10))).isTrue();
        assertThat(target.isHealthy()).isTrue();
    }

    static String key(String className, String method, String snippet) throws Exception {
        Path source = repositoryRoot().resolve(SOURCE + className.replace('.', '/') + ".java");
        List<String> lines = Files.readAllLines(source);
        List<Integer> matches = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains(snippet)).boxed().toList();
        assertThat(matches).as("Unique executable source snippet: %s", snippet).hasSize(1);
        return className + "#" + method + ":" + (matches.getFirst() + 1);
    }

    String postKey() throws Exception {
        return key(CONTROLLER, "getPost", "return postService.getPost(postId);");
    }

    HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path))
                .timeout(Duration.ofSeconds(5)).header("authorization", "Bearer alice-token")
                .header("x-run-as-tenant", "fixture-tenant").header("x-run-as-user", "alice")
                .header("content-type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    JsonNode status(String key) throws Exception {
        return success("status", Map.of("key", key)).path("entries").get(0);
    }

    void hit(String key, String path) throws Exception {
        success("reset", Map.of("key", key));
        int id = nextId++;
        server.send(executeRequest(id, "probe.wait_for_hit", Map.of("key", key, "baseUrl", baseUrl,
                "timeoutMs", 4_000, "pollIntervalMs", 50, "maxRetries", 1), false));
        // Repeated real requests tolerate baseline scheduling without accepting historical hits.
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(http("GET", path, null).statusCode()).isEqualTo(200);
            Thread.sleep(100);
        }
        JsonNode wait = toolPayload(server.responseFor(id)).path("result");
        assertThat(wait.path("reasonCode").asText()).as(wait.toPrettyString()).isEqualTo("SUCCESS");
        assertThat(wait.path("actionResult").path("key").asText()).isEqualTo(key);
        assertThat(wait.path("actionResult").path("outcome").asText()).isEqualTo("LINE_HIT");
        assertThat(status(key).path("hitCount").asLong()).isPositive();
        JavaItScenario.observe("probe.wait_for_hit", "LINE_HIT", key);
    }

    @Override
    public void close() throws Exception {
        try {
            server.close();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        } finally {
            target.close();
        }
    }
}
