package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mstatiming.PerformanceTimingFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("performance")
@Timeout(360)
class McpServerPerformanceTimingIT extends PerformanceMcpSupport {
    private static final String TYPE = PerformanceTimingFixture.class.getName();
    private static final double METHOD_ONE_MIN_MS = 2500;
    private static final double METHOD_ONE_MAX_MS = 10000;

    @Test
    void repeatedWallEstimatesAllowNormalVariationAndRejectAMinuteOutlier() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (SpringFixtureTargetProcess target = SpringFixtureTargetProcess.start(fixtureJar(workspace),
                    List.of("-Djava.io.tmpdir=" + Files.createDirectories(workspace.resolve("tmp"))), List.of());
                McpServerProcess server = JvmProbeScenario.startServer(workspace);
                HttpClient client = HttpClient.newHttpClient()) {
            target.awaitHealthy();
            initialize(server);
            int probePort = freePort();
            JsonNode attach = execute(server, "jvm_lifecycle.attach", Map.of("pid", Long.toString(target.pid()),
                    "expectedProcessStartEpochMs", target.processStartEpochMs(), "probePort", probePort,
                    "include", "com.example.mstatiming.**"), true);
            assertThat(attach.path("result").path("reasonCode").asText()).as(attach.toPrettyString()).isEqualTo("active");
            createProject(server, workspace);
            List<ObjectNode> calibrations = new ArrayList<>();
            for (int first : new int[] {5000, 6000, 7000, 60000}) {
                ObjectNode plan = calibrationPlan(target.port(), probePort, first);
                ok(server, "performance_plan.upsert", planInput(PROFILE, plan));
                int id = 800 + first;
                String runId = "timing-" + first;
                server.send(executeRequest(id, "performance_suite.execute_plan", selector("plan", runId), true));
                JsonNode result = toolPayload(server.responseFor(id, Duration.ofSeconds(100))).path("result");
                assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
                JsonNode msta = result.path("details").path("msta");
                assertThat(msta.path("timingBasis").asText()).isEqualTo("sampled_wall_time");
                assertThat(msta.path("sampling").path("complete").asBoolean()).isTrue();
                assertThat(msta.path("sampling").path("availableProcessors").asInt()).isPositive();
                JsonNode truth = JSON.readTree(client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + target.port() + "/last")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
                double one = estimate(msta, "methodOne");
                if (first == 60000) {
                    assertThat(one).isGreaterThan(45000);
                    assertThatThrownBy(() -> assertMethodOneRange(one)).isInstanceOf(AssertionError.class);
                } else {
                    assertMethodOneRange(one);
                    assertThat(estimate(msta, "methodTwo")).isBetween(5000.0, 15000.0);
                    assertThat(estimate(msta, "methodThree")).isBetween(7500.0, 20000.0);
                    // Hardware speed changes this fixed CPU workload; compare with the independent timer from THIS run.
                    double actualCpu = truth.path("cpuWork").asDouble();
                    assertThat(estimate(msta, "cpuWork")).isBetween(actualCpu * 0.5, actualCpu + 5000.0);
                }
                Path run = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/performance/plan/runs/" + runId + "-1");
                JsonNode persisted = JSON.readTree(run.resolve("execution.result.json").toFile());
                assertThat(persisted.path("details").path("msta")).isEqualTo(msta);
                ObjectNode evidence = JSON.createObjectNode().put("firstSleepMs", first);
                evidence.put("calibrationResult", first == 60000 ? "fail_out_of_range" : "pass")
                        .put("methodOneMinMs", METHOD_ONE_MIN_MS).put("methodOneMaxMs", METHOD_ONE_MAX_MS);
                evidence.set("independentElapsedMs", truth);
                evidence.set("msta", msta);
                calibrations.add(evidence);
                JavaItScenario.observe("performance_suite.execute_plan", "ASSERTED", "wall-calibration:" + first);
                assertThat(execute(server, "probe.profiler", Map.of("baseUrl", "http://127.0.0.1:" + probePort,
                        "action", "reset"), true).path("result").path("reasonCode").asText()).isEqualTo("SUCCESS");
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(workspace.resolve("msta-calibration.json").toFile(), calibrations);
            assertThat(execute(server, "jvm_lifecycle.deactivate", Map.of("pid", Long.toString(target.pid()),
                    "expectedProcessStartEpochMs", target.processStartEpochMs()), true)
                    .path("result").path("reasonCode").asText()).isEqualTo("deactivated");
            assertThat(target.isAlive()).isTrue();
            assertThat(target.isHealthy()).isTrue();
        }
    }

    private ObjectNode calibrationPlan(int targetPort, int probePort, int first) throws Exception {
        ObjectNode plan = plan(PROFILE, "http://127.0.0.1:" + targetPort);
        ObjectNode contract = contract(plan);
        contract.putObject("workloadProvider").put("type", "builtin");
        contract.withObject("loadModel").put("concurrency", 1).put("durationSeconds", 1).put("rampUpSeconds", 0);
        contract.withObject("successCriteria").put("p95LatencyMs", 90000).put("minThroughputPerSec", 0.000001);
        entry(plan).withObject("request").put("path", "/timing/" + first).put("timeoutMs", 90000);
        ObjectNode analysis = contract.putObject("analysis");
        analysis.putObject("executionTiming").put("enabled", true).put("provider", "jfr").put("event", "wall")
                .put("intervalNanos", 100_000_000);
        ObjectNode msta = analysis.putObject("msta").put("enabled", true).put("mode", "method_targets");
        ObjectNode targets = contract.putObject("observationTargets").put("probeBaseUrl", "http://127.0.0.1:" + probePort);
        for (String method : first == 60000 ? List.of("methodOne") : List.of("methodOne", "methodTwo", "methodThree", "cpuWork")) {
            msta.withArray("methodTargets").add(TYPE + "#" + method);
            targets.withArray("requiredLineHits").add(lineKey(method));
        }
        analysis.putObject("correlation").put("enabled", true).put("kind", "sampled_attribution")
                .put("anchorSource", "msta_resolved_anchors").put("requireLineHit", true).put("requireMsta", true);
        return plan;
    }

    private double estimate(JsonNode msta, String method) {
        JsonNode measured = java.util.stream.StreamSupport.stream(msta.path("methods").spliterator(), false)
                .filter(value -> value.path("methodRef").asText().equals(TYPE + "#" + method)).findFirst().orElseThrow();
        assertThat(measured.path("samples").asInt()).isGreaterThan(1);
        return measured.path("estimatedTimeMs").asDouble();
    }

    private void assertMethodOneRange(double estimatedMs) {
        assertThat(estimatedMs).as("fixed calibration range; do not move it to fit an outlier")
                .isBetween(METHOD_ONE_MIN_MS, METHOD_ONE_MAX_MS);
    }

    private String lineKey(String method) throws Exception {
        List<String> source = Files.readAllLines(repositoryRoot().resolve("mcp-server/application/src/test/java/"
                + TYPE.replace('.', '/') + ".java"));
        String needle = switch (method) {
            case "methodOne" -> "Thread.sleep(duration);";
            case "methodTwo" -> "Thread.sleep(10_000);";
            case "methodThree" -> "Thread.sleep(15_000);";
            default -> "sink = value;";
        };
        int line = java.util.stream.IntStream.range(0, source.size()).filter(index -> source.get(index).trim().equals(needle))
                .findFirst().orElseThrow() + 1;
        return TYPE + "#" + method + ":" + line;
    }

    private Path fixtureJar(Path workspace) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, TYPE);
        Path jar = workspace.resolve("msta-fixture.jar");
        String name = TYPE.replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var input = PerformanceTimingFixture.class.getResourceAsStream("/" + name)) {
            output.putNextEntry(new JarEntry(name));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }
}
