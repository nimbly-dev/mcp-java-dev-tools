package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Generated Performance contracts and real Artifact assertions over the shared MCP driver. */
abstract class PerformanceMcpSupport extends ArtifactMcpSupport {
    static final String PROFILE = "performance";

    static ObjectNode context(JvmProbeScenario s, String... names) {
        ObjectNode project = project(s.workspace);
        ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
        root.remove("variables");
        root.putArray("executionProfiles");
        ObjectNode profile = root.withArray("executionProfiles").addObject()
                .put("executionProfile", PROFILE).put("suiteType", PROFILE).put("executionPolicy", "stop_on_fail");
        profile.putObject("providedContext").put("base", "http://127.0.0.1:" + s.target.port());
        for (int index = 0; index < names.length; index++) {
            profile.withArray("plans").addObject().put("order", index + 1).put("planName", names[index]).put("onFail", "inherit");
        }
        return project;
    }

    static void saveContext(JvmProbeScenario s, String... names) throws Exception {
        ok(s.server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", context(s, names)));
    }

    static ObjectNode workload(JvmProbeScenario s) throws Exception {
        ObjectNode plan = plan(PROFILE, "http://127.0.0.1:" + s.target.port());
        ObjectNode contract = contract(plan);
        contract.withObject("workloadProvider").putObject("options").put("installationPath", jmeterHome().toString());
        contract.withObject("loadModel").put("concurrency", 2).put("rampUpSeconds", 0).put("durationSeconds", 2);
        contract.withObject("successCriteria").put("minThroughputPerSec", 0.000001).put("p95LatencyMs", 300000);
        entry(plan).withObject("transport").put("healthCheckPath", "/actuator/health").put("wrappedOnly", true);
        entry(plan).withObject("request").put("path", "/api/v1/posts/101?source=performance&value=a%20b")
                .putObject("headers").put("Authorization", "Bearer alice-token")
                .put("x-run-as-user", "alice").put("x-run-as-tenant", "fixture-tenant");
        contract.putObject("observationTargets").put("probeBaseUrl", s.baseUrl)
                .putArray("requiredLineHits").add(s.postKey());
        return plan;
    }

    static ObjectNode contract(ObjectNode plan) {
        return (ObjectNode) plan.path("contract");
    }

    static ObjectNode entry(ObjectNode plan) {
        return (ObjectNode) plan.path("contract").path("entrypoints").get(0);
    }

    static void save(JvmProbeScenario s, String name, ObjectNode plan) throws Exception {
        ok(s.server, "performance_plan.upsert", Map.of("projectName", PROJECT, "planName", name, "payload", plan));
        JsonNode read = ok(s.server, "performance_plan.read", Map.of("projectName", PROJECT, "planName", name,
                "query", Map.of("select", List.of("metadata", "contract"))));
        ObjectNode expected = contract(plan).deepCopy();
        JsonNode headers = expected.path("entrypoints").get(0).path("request").path("headers");
        if (headers.has("Authorization")) {
            ((ObjectNode) headers).put("Authorization", "***REDACTED***");
        }
        assertThat(read.path("artifact").path("contract")).isEqualTo(expected);
    }

    static Map<String, Object> selector(String name, String run) {
        return Map.of("projectName", PROJECT, "executionProfile", PROFILE, "planName", name, "suiteRunId", run);
    }

    static JsonNode run(JvmProbeScenario s, String name, String run) throws Exception {
        return s.result("performance_suite.execute_plan", selector(name, run), true);
    }

    static Path runDirectory(JvmProbeScenario s, String name, String run) {
        return s.workspace.resolve(".mcpjvm/" + PROJECT + "/plans/performance/" + name + "/runs/" + run + "-1");
    }

    static JsonNode assertRun(JvmProbeScenario s, String name, String run, String status) throws Exception {
        Path path = runDirectory(s, name, run).resolve("execution.result.json");
        assertThat(path).isRegularFile();
        JsonNode persisted = JSON.readTree(path.toFile());
        assertThat(persisted.path("status").asText()).as(persisted.toPrettyString()).isEqualTo(status);
        JsonNode read = ok(s.server, "run_result.read", Map.of("projectName", PROJECT, "suiteType", PROFILE,
                "planName", name, "runId", run + "-1"));
        assertThat(read.path("artifact")).isEqualTo(persisted);
        return persisted;
    }

    static void assertBlocked(JvmProbeScenario s, String name, String reason) throws Exception {
        JsonNode result = run(s, name, name);
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("blocked");
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(result.path("details").has("runStatus")).isFalse();
        assertRun(s, name, name, "blocked");
    }

    static void assertNoWorkload(JvmProbeScenario s, String name) {
        assertThat(runDirectory(s, name, name).resolve("workload.jmeter.jmx")).doesNotExist();
    }

    static void assertMetrics(JsonNode details, Path directory) throws Exception {
        List<String> rows = Files.readAllLines(directory.resolve("workload.jmeter.jtl"));
        String[] headers = rows.getFirst().split(",");
        int elapsed = List.of(headers).indexOf("elapsed");
        int success = List.of(headers).indexOf("success");
        assertThat(elapsed).isNotNegative();
        assertThat(success).isNotNegative();
        List<Long> latencies = rows.stream().skip(1).map(row -> Math.max(1, Long.parseLong(row.split(",")[elapsed])))
                .sorted().toList();
        long failures = rows.stream().skip(1).filter(row -> !row.split(",")[success].equals("true")).count();
        assertThat(latencies).isNotEmpty();
        JsonNode metrics = details.path("metrics");
        assertThat(metrics.path("totalRequests").asInt()).isEqualTo(latencies.size());
        assertThat(metrics.path("failedRequests").asLong()).isEqualTo(failures);
        assertThat(metrics.path("errorRatePct").asDouble()).isEqualTo(failures * 100.0 / latencies.size());
        assertThat(metrics.path("throughputPerSec").asDouble()).isEqualTo(latencies.size() / 2.0);
        assertThat(metrics.path("p95LatencyMs").asLong()).isEqualTo(latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1));
        for (String field : List.of("maxErrorRatePct", "minThroughputPerSec", "p95LatencyMs")) {
            JsonNode threshold = details.path("thresholdResults").path(field);
            boolean expected = threshold.path("actual").asDouble() <= threshold.path("limit").asDouble();
            if (field.equals("minThroughputPerSec")) {
                expected = threshold.path("actual").asDouble() >= threshold.path("limit").asDouble();
            }
            assertThat(threshold.path("pass").asBoolean()).as(field).isEqualTo(expected);
        }
    }
}
