package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("performance")
@Timeout(240)
class McpServerPerformanceWorkloadIT extends PerformanceMcpSupport {
    @Test
    void jmeterEvaluatesAndEncodesBoundQueryCredentialsAtRuntime() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start();
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                TransportHttpTarget proxy = TransportHttpTarget.start(exchange -> {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://127.0.0.1:" + s.target.port() + exchange.getRequestURI()))
                            .header("Authorization", "Bearer alice-token").header("x-run-as-user", "alice")
                            .header("x-run-as-tenant", "fixture-tenant").GET().build();
                    try {
                        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                        TransportHttpTarget.respond(exchange, response.statusCode(), response.body());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(exception);
                    }
                })) {
            s.attach();
            String credential = "synthetic +&=%?/雪";
            Files.writeString(s.workspace.resolve("query.env"), "QUERY_TOKEN=" + credential + "\n");
            ObjectNode project = context(s, "query");
            ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
            root.put("envFile", "query.env").putObject("variables").putObject("contextBindings").put("token", "QUERY_TOKEN");
            ok(s.server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
            ObjectNode plan = workload(s);
            entry(plan).withObject("transport").put("baseUrl", proxy.url(""));
            entry(plan).withObject("transport").remove("healthCheckPath");
            entry(plan).withObject("request").putObject("queryTemplate").put("bound", "prefix ${token} suffix")
                    .put("second", "${token}").put("literal", "literal +&=%?/雪").put("source", "overridden");
            save(s, "query", plan);
            JsonNode result = run(s, "query", "query");
            assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            Map<String, String> query = decodedQuery(proxy.received().uri());
            assertThat(query).containsEntry("bound", "prefix " + credential + " suffix").containsEntry("second", credential)
                    .containsEntry("literal", "literal +&=%?/雪").containsEntry("source", "overridden").containsEntry("value", "a b");
            Path directory = runDirectory(s, "query", "query");
            String jmx = Files.readString(directory.resolve("workload.jmeter.jmx"));
            assertThat(jmx).contains("${__urlencode(${__groovy(System.getenv(&apos;QUERY_TOKEN&apos;))})}")
                    .doesNotContain(credential, java.net.URLEncoder.encode(credential, java.nio.charset.StandardCharsets.UTF_8));
            for (String suffix : new String[] {"jtl", "log"}) {
                assertThat(Files.readString(directory.resolve("workload.jmeter." + suffix)))
                        .doesNotContain(credential, java.net.URLEncoder.encode(credential, java.nio.charset.StandardCharsets.UTF_8));
            }
            assertThat(Files.readAllLines(directory.resolve("workload.jmeter.jtl")).getFirst().split(",")).doesNotContain("URL");
            assertMetrics(result.path("details"), directory);
            assertThat(assertRun(s, "query", "query", "pass").toString()).doesNotContain(credential);
            s.deactivate();
        }
    }

    private Map<String, String> decodedQuery(String url) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String pair : java.net.URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return values;
    }

    @Test
    @Tag("performance-smoke")
    void realCliMetricsThresholdsFreshLineHitsAndPersistedResults() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "pass", "threshold", "errors", "no-hit");
            ObjectNode plan = workload(s);
            save(s, "pass", plan);
            assertThat(s.execute("performance_suite.execute_plan", selector("pass", "pass"), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            assertNoWorkload(s, "pass");
            s.hit(s.postKey(), "/api/v1/posts/101");
            JsonNode result = run(s, "pass", "pass");
            assertCompleted(s, "pass", result, "pass");
            assertThat(s.status(s.postKey()).path("hitCount").asLong()).isPositive();
            assertThat(result.path("details").path("requiredLineHits").get(0).asText()).isEqualTo(s.postKey());
            assertThat(result.path("details").path("profiler").path("status").asText()).isEqualTo("not_configured");
            assertThat(result.path("details").path("msta").path("status").asText()).isEqualTo("not_configured");
            assertThat(run(s, "pass", "pass").path("details").path("thresholdResults"))
                    .isEqualTo(result.path("details").path("thresholdResults"));
            contract(plan).withObject("successCriteria").put("minThroughputPerSec", 1.0e100);
            save(s, "threshold", plan);
            assertCompleted(s, "threshold", run(s, "threshold", "threshold"), "fail");
            plan = workload(s);
            entry(plan).withObject("request").put("path", "/api/v1/posts/999999");
            save(s, "errors", plan);
            JsonNode errors = run(s, "errors", "errors");
            assertCompleted(s, "errors", errors, "fail");
            assertThat(errors.path("details").path("metrics").path("errorRatePct").asDouble()).isEqualTo(100.0);
            plan = workload(s);
            entry(plan).withObject("request").put("path", "/actuator/health");
            save(s, "no-hit", plan);
            assertBlocked(s, "no-hit", "performance_required_line_hit_missing");
            assertThat(s.status(s.postKey()).path("lineHit").asBoolean()).isFalse();
            s.deactivate();
        }
    }

    @Test
    void requestBodyQueryHeadersAndRuntimeCredentialsReachTheOwnedFixture() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveCredentialContext(s);
            ObjectNode plan = workload(s);
            entry(plan).withObject("transport").put("baseUrl", "${base}");
            entry(plan).withObject("request").put("method", "PUT").put("path", "/api/v1/posts/101?source=perf&value=a%20b")
                    .put("body", "{\"content\":\"${content}\",\"visibility\":\"PUBLIC\",\"tags\":[\"perf\"]}");
            entry(plan).withObject("request").withObject("headers").put("Authorization", "${authorization}")
                    .put("Content-Type", "application/json");
            contract(plan).withObject("observationTargets").putArray("requiredLineHits")
                    .add(JvmProbeScenario.key(JvmProbeScenario.CONTROLLER, "updatePost", "return postService.updatePost(postId, request);"));
            save(s, "put", plan);
            JsonNode result = run(s, "put", "put");
            assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            assertThat(s.http("GET", "/api/v1/posts/101", null).body()).contains("performance & body");
            Path directory = runDirectory(s, "put", "put");
            assertMetrics(result.path("details"), directory);
            String jmx = Files.readString(directory.resolve("workload.jmeter.jmx"));
            assertThat(jmx).contains("PUT", "a%20b", "&amp;", "System.getenv", "FIXTURE_AUTH").doesNotContain("alice-token");
            assertThat(assertRun(s, "put", "put", "pass").toString()).doesNotContain("alice-token");
            s.deactivate();
        }
    }

    private void saveCredentialContext(JvmProbeScenario s) throws Exception {
        Files.writeString(s.workspace.resolve("credentials.env"), "FIXTURE_AUTH=Bearer alice-token\n");
        ObjectNode project = context(s, "put");
        ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
        root.put("envFile", "credentials.env").putObject("variables").putObject("contextBindings")
                .put("authorization", "FIXTURE_AUTH");
        ((ObjectNode) root.path("executionProfiles").get(0).path("providedContext")).put("content", "performance & body");
        ok(s.server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
    }

    private void assertCompleted(JvmProbeScenario s, String name, JsonNode result, String status) throws Exception {
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("completed");
        JsonNode details = result.path("details");
        assertThat(details.path("runStatus").asText()).isEqualTo(status);
        Path directory = runDirectory(s, name, name);
        assertMetrics(details, directory);
        JsonNode persisted = assertRun(s, name, name, status);
        assertThat(persisted.path("directResult").path("details").path("thresholdResults"))
                .isEqualTo(details.path("thresholdResults"));
        assertThat(persisted.path("directResult").path("details").path("metrics").path("totalRequests"))
                .isEqualTo(details.path("metrics").path("totalRequests"));
        for (String field : List.of("jmxPath", "jtlPath", "logPath")) {
            Path path = Path.of(details.path("artifacts").path(field).asText());
            assertThat(path.startsWith(directory)).isTrue();
            assertThat(path).isRegularFile();
        }
        String jmx = Files.readString(directory.resolve("workload.jmeter.jmx"));
        assertThat(jmx).contains("ThreadGroup.num_threads\">2", "ThreadGroup.duration\">2", "HTTPSampler.method\">GET");
    }

    @Test
    void applicationDiscoversRealJmeterThroughItsEnvironmentAndPathPrerequisiteContract() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "environment", "path", "executable");
            for (String name : new String[] {"environment", "path", "executable"}) {
                ObjectNode plan = workload(s);
                ObjectNode options = contract(plan).withObject("workloadProvider").withObject("options");
                options.remove("installationPath");
                Map<String, String> environment = Map.of("MCP_JAVA_DEV_TOOLS_JMETER_HOME", jmeterHome().toString());
                if (name.equals("path")) {
                    environment = Map.of("PATH", jmeterHome().resolve("bin") + java.io.File.pathSeparator
                            + System.getenv().getOrDefault("PATH", ""));
                } else if (name.equals("executable")) {
                    boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
                    options.put("installationPath", jmeterHome().resolve("bin").resolve(windows ? "jmeter.bat" : "jmeter").toString());
                    environment = Map.of();
                }
                Map<String, String> isolatedEnvironment = new java.util.LinkedHashMap<>(environment);
                isolatedEnvironment.put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=\"" + s.workspace.resolve("tmp") + "\"");
                save(s, name, plan);
                try (McpServerProcess isolated = McpServerProcess.startWithEnvironment(jarPath(), s.workspace, isolatedEnvironment,
                        "--workspace-root=" + s.workspace)) {
                    initialize(isolated);
                    JsonNode result = execute(isolated, "performance_suite.execute_plan", selector(name, name), true).path("result");
                    assertThat(result.path("details").path("runStatus").asText()).as(name).isEqualTo("pass");
                    assertMetrics(result.path("details"), runDirectory(s, name, name));
                    assertRun(s, name, name, "pass");
                }
            }
            s.deactivate();
        }
    }

    @Test
    void optionalWorkloadArtifactEmissionDoesNotDisableExecutionOrMetricCollection() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "compact");
            ObjectNode plan = workload(s);
            contract(plan).withObject("workloadProvider").withObject("options")
                    .put("emitJmx", false).put("emitJtl", false).put("emitLog", false);
            save(s, "compact", plan);
            JsonNode result = run(s, "compact", "compact");
            assertThat(result.path("status").asText()).isEqualTo("completed");
            assertThat(result.path("details").path("metrics").path("totalRequests").asInt()).isPositive();
            assertThat(result.path("details").path("artifacts")).isEmpty();
            for (String suffix : new String[] {"jmx", "jtl", "log"}) {
                assertThat(runDirectory(s, "compact", "compact").resolve("workload.jmeter." + suffix)).doesNotExist();
            }
            assertRun(s, "compact", "compact", "pass");
            s.deactivate();
        }
    }

    @Test
    void registrySelectedProbeVerifiesEveryRequiredKeyInOrderAndNormalizesDuplicates() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            saveContext(s, "batch");
            ObjectNode registry = McpServerArtifactConfigIT.registry(s.workspace);
            ((ObjectNode) registry.path("profiles").path("local").path("probes").path("one")).put("baseUrl", s.baseUrl);
            ok(s.server, "probe_config.upsert", Map.of("payload", registry));
            assertThat(artifact(s.server, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
            ObjectNode plan = workload(s);
            String serviceKey = JvmProbeScenario.key(JvmProbeScenario.SERVICE, "getPost", "return toDetail(requirePost(postId));");
            ObjectNode targets = contract(plan).withObject("observationTargets");
            targets.remove("probeBaseUrl");
            targets.put("probeId", "one").putArray("requiredLineHits").add(s.postKey()).add(serviceKey).add(s.postKey());
            save(s, "batch", plan);
            JsonNode result = run(s, "batch", "batch");
            assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            assertThat(result.path("details").path("requiredLineHits")).extracting(JsonNode::asText)
                    .containsExactly(s.postKey(), serviceKey);
            assertThat(s.status(s.postKey()).path("hitCount").asLong()).isPositive();
            assertThat(s.status(serviceKey).path("hitCount").asLong()).isPositive();
            assertRun(s, "batch", "batch", "pass");
            s.deactivate();
        }
    }
}
