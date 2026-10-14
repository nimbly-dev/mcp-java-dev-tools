package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("performance")
@Timeout(180)
class McpServerPerformanceValidationIT extends PerformanceMcpSupport {
    @Test
    void invalidLoadThresholdObservationAndContextInputsFailBeforeWorkload() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            String[] cases = {"concurrency", "duration", "ramp", "errors", "throughput", "latency",
                    "lines", "selector", "context", "msta", "provider", "mode"};
            saveContext(s, cases);
            ObjectNode zero = workload(s);
            contract(zero).withObject("loadModel").put("concurrency", 0);
            blocked(s.server, "performance_plan.upsert", Map.of("projectName", PROJECT, "planName", "zero", "payload", zero),
                    "performance_load_model_invalid");
            for (String name : cases) {
                ObjectNode plan = workload(s);
                String reason = invalidate(plan, name);
                save(s, name, plan);
                assertBlocked(s, name, reason);
                assertNoWorkload(s, name);
            }
        }
    }

    private String invalidate(ObjectNode plan, String name) {
        ObjectNode contract = contract(plan);
        switch (name) {
            case "zero" -> contract.withObject("loadModel").put("concurrency", 0);
            case "concurrency" -> contract.withObject("loadModel").put("concurrency", 10001);
            case "duration" -> contract.withObject("loadModel").put("durationSeconds", 36001);
            case "ramp" -> contract.withObject("loadModel").put("rampUpSeconds", -1);
            case "errors" -> contract.withObject("successCriteria").put("maxErrorRatePct", -1);
            case "throughput" -> contract.withObject("successCriteria").put("minThroughputPerSec", 0);
            case "latency" -> contract.withObject("successCriteria").put("p95LatencyMs", 0);
            case "lines" -> contract.withObject("observationTargets").putArray("requiredLineHits");
            case "selector" -> contract.withObject("observationTargets").remove("probeBaseUrl");
            case "context" -> entry(plan).withObject("request").put("body", "${missingContext}");
            case "msta" -> contract.putObject("analysis").putObject("msta").put("enabled", true);
            case "provider" -> contract.withObject("workloadProvider").put("type", "unknown");
            case "mode" -> contract.withObject("workloadProvider").put("mode", "imported_jmx");
            default -> throw new AssertionError(name);
        }
        return switch (name) {
            case "concurrency", "duration" -> "performance_plan_bounds_invalid";
            case "errors", "throughput", "latency" -> "performance_threshold_invalid";
            case "lines", "selector" -> "performance_required_line_hit_missing";
            case "context" -> "performance_runtime_context_unresolved";
            case "msta" -> "performance_msta_profiler_required";
            default -> "performance_plan_input_invalid";
        };
    }

    @Test
    void unavailableTargetProbeInvalidKeyAndUnavailablePlanStayBlocked() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            saveContext(s, "target", "probe", "key", "absent");
            ObjectNode plan = workload(s);
            entry(plan).withObject("transport").put("baseUrl", "http://127.0.0.1:" + freePort());
            save(s, "target", plan);
            assertBlocked(s, "target", "performance_target_healthcheck_failed");
            assertNoWorkload(s, "target");
            plan = workload(s);
            save(s, "probe", plan);
            assertBlocked(s, "probe", "performance_probe_reset_failed");
            assertNoWorkload(s, "probe");
            s.attach();
            plan = workload(s);
            contract(plan).withObject("observationTargets").putArray("requiredLineHits").add("invalid-line-key");
            save(s, "key", plan);
            assertBlocked(s, "key", "performance_probe_reset_failed");
            assertNoWorkload(s, "key");
            JsonNode absent = run(s, "absent", "absent");
            assertThat(absent.path("reasonCode").asText()).isEqualTo("direct_suite_plan_unavailable");
            JsonNode invalid = s.execute("performance_suite.execute_plan", Map.of("projectName", PROJECT), true);
            assertThat(invalid.path("status").asText()).isEqualTo("invalid_input");
            assertThat(run(s, "../escape", "invalid").path("reasonCode").asText()).isEqualTo("direct_suite_selector_invalid");
            s.deactivate();
        }
    }

    @Test
    void missingAndInvalidJmeterPrerequisitesAreExplicitBlockedFailures() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            saveContext(s, "missing", "invalid");
            ObjectNode plan = workload(s);
            contract(plan).withObject("workloadProvider").withObject("options")
                    .put("installationPath", s.workspace.resolve("missing-jmeter").toString());
            save(s, "missing", plan);
            try (McpServerProcess isolated = McpServerProcess.startWithEnvironment(jarPath(), s.workspace,
                    Map.of("PATH", ""), "--workspace-root=" + s.workspace)) {
                initialize(isolated);
                JsonNode result = execute(isolated, "performance_suite.execute_plan", selector("missing", "missing"), true);
                assertThat(result.path("result").path("reasonCode").asText()).isEqualTo("performance_jmeter_missing");
                assertRun(s, "missing", "missing", "blocked");
                assertNoWorkload(s, "missing");
            }
            s.attach();
            Path invalid = Files.writeString(s.workspace.resolve("invalid-jmeter"), "invalid executable\n");
            contract(plan).withObject("workloadProvider").withObject("options").put("installationPath", invalid.toString());
            save(s, "invalid", plan);
            assertBlocked(s, "invalid", "performance_jmeter_artifact_failed");
            s.deactivate();
        }
    }

    @Test
    void invalidRetainedAnalysisOptionsAreRejectedBeforeAnyWorkload() throws Exception {
        String[][] invalid = {
            {"/analysis/executionTiming", "[]"}, {"/analysis/executionTiming", "{\"enabled\":\"true\"}"},
            {"/analysis/executionTiming", "{\"enabled\":true,\"provider\":\"unknown\"}"},
            {"/analysis/executionTiming", "{\"enabled\":true,\"intervalNanos\":0}"},
            {"/analysis/executionTiming", "{\"enabled\":true,\"intervalNanos\":1.5}"},
            {"/analysis/executionTiming", "{\"enabled\":true,\"outputFormat\":\"html\"}"},
            {"/analysis/msta", "[]"}, {"/analysis/msta", "{\"enabled\":true,\"mode\":\"unknown\"}"},
            {"/analysis/msta", "{\"enabled\":true,\"methodTargets\":[]}"},
            {"/analysis/msta", "{\"enabled\":true,\"methodTargets\":[\"invalid\"]}"},
            {"/analysis/msta", "{\"enabled\":true,\"includePackages\":[3]}"},
            {"/analysis/msta", "{\"enabled\":true,\"allowThirdPartyFrames\":\"false\"}"},
            {"/analysis/msta", "{\"enabled\":true,\"anchorSelection\":{\"source\":\"unknown\",\"strategy\":\"all\"}}"},
            {"/analysis/correlation", "[]"}, {"/analysis/correlation", "{\"enabled\":true}"}
        };
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            String[] names = java.util.stream.IntStream.range(0, invalid.length).mapToObj(index -> "analysis-" + index).toArray(String[]::new);
            saveContext(s, names);
            for (int index = 0; index < invalid.length; index++) {
                ObjectNode plan = workload(s);
                ObjectNode analysis = contract(plan).putObject("analysis");
                analysis.putObject("executionTiming").put("enabled", true).put("provider", "jfr");
                String pointer = invalid[index][0];
                analysis.set(pointer.substring(pointer.lastIndexOf('/') + 1), JSON.readTree(invalid[index][1]));
                save(s, names[index], plan);
                assertBlocked(s, names[index], "performance_plan_input_invalid");
                assertNoWorkload(s, names[index]);
            }
        }
    }

    @Test
    void catalogAndDescribeExposeOnlyTheCurrentPerformanceOperationAndItsSafetyContract() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            server.send(request(30, "tools/call", Map.of("name", "operation_catalog", "arguments", Map.of("api", "performance_suite"))));
            assertThat(toolPayload(server.responseFor(30)).path("entries"))
                    .extracting(entry -> entry.path("operationId").asText()).containsExactly("performance_suite.execute_plan");
            server.send(request(31, "tools/call", Map.of("name", "operation_describe", "arguments",
                    Map.of("operationId", "performance_suite.execute_plan"))));
            JsonNode described = toolPayload(server.responseFor(31)).path("operation");
            assertThat(described.path("inputSchema").path("required")).extracting(JsonNode::asText)
                    .containsExactlyInAnyOrder("projectName", "executionProfile", "planName", "suiteRunId");
            assertThat(described.path("safety").path("confirmationRequired").asBoolean()).isTrue();
            assertThat(described.path("safety").path("cancellationSupported").asBoolean()).isFalse();
        }
    }
}
