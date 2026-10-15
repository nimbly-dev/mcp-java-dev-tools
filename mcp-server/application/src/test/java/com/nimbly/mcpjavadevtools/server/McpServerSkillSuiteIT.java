package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Same project/plan/run names across suite types must retain their own runtime evidence. */
@Tag("skill-workflows")
@Timeout(240)
class McpServerSkillSuiteIT extends OrchestrationMcpSupport {
    @Test
    void lifecycleRegistryAndAllSuiteResultsRemainConsumableAfterRestart() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start(); TransportHttpTarget target = TransportHttpTarget.start()) {
            assertDiscovery(s);
            s.attach();
            registry(s);
            JsonNode reachable = s.result("probe.check", Map.of("probeId", "one"), false);
            assertThat(reachable.path("reasonCode").asText()).isEqualTo("SUCCESS");
            ok(s.server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project(s.workspace)));
            ok(s.server, "project_context.validate", Map.of("projectName", PROJECT));
            runSuites(s, target);
            try (McpServerProcess restarted = start(s.workspace)) {
                assertThat(artifact(restarted, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
                ok(restarted, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
                for (String suite : List.of("regression", "performance", "security")) {
                    assertResult(restarted, s, suite);
                }
                assertThat(query(restarted, "run_state", Map.of("runId", "shared-1")).path("items")).hasSize(3);
                // Persisted line observations are historical. A later reset controls live runtime state.
                s.success("reset", Map.of("key", s.postKey()));
                assertThat(s.status(s.postKey()).path("hitCount").asLong()).isZero();
                assertResult(restarted, s, "performance");
                s.deactivate();
            }
        }
        JavaItScenario.observe("artifact_management.run_result.read", "ASSERTED", "suite_type_isolation_restart_live_vs_historical");
    }

    private void assertDiscovery(JvmProbeScenario s) throws Exception {
        JsonNode result = s.result("jvm_lifecycle.list_jvms", Map.of(), false);
        assertThat(result.toString()).contains(Long.toString(s.target.pid()));
        assertThat(s.execute("jvm_lifecycle.attach", s.attachArguments(), false).path("status").asText())
                .isEqualTo("confirmation_required");
        assertThat(s.target.isHealthy()).isTrue();
    }

    private void registry(JvmProbeScenario s) throws Exception {
        ObjectNode registry = McpServerArtifactConfigIT.registry(s.workspace);
        ((ObjectNode) registry.path("profiles").path("local").path("probes").path("one")).put("baseUrl", s.baseUrl);
        ((ObjectNode) registry.path("profiles").path("local").path("probes")).remove("two");
        ok(s.server, "probe_config.upsert", Map.of("payload", registry));
        ok(s.server, "probe_config.validate", Map.of());
        assertThat(ok(s.server, "probe_config.read", Map.of()).path("artifact")).isEqualTo(registry);
    }

    private void runSuites(JvmProbeScenario s, TransportHttpTarget target) throws Exception {
        String url = "http://127.0.0.1:" + s.target.port();
        save(s.server, "plan", generatedPlan(url + "/actuator/health"));
        JsonNode regression = execute(s.server, "regression_suite.execute_plan", suiteInput("regression"), true).path("result");
        assertThat(regression.path("details").path("runStatus").asText()).isEqualTo("pass");
        ObjectNode performance = builtin(s, url);
        ok(s.server, "performance_plan.upsert", Map.of("projectName", PROJECT, "planName", "plan", "payload", performance));
        JsonNode perf = execute(s.server, "performance_suite.execute_plan", suiteInput("performance"), true).path("result");
        assertThat(perf.path("details").path("runStatus").asText()).as(perf.toPrettyString()).isEqualTo("pass");
        assertThat(perf.path("details").path("metrics").path("totalRequests").asInt()).isPositive();
        assertThat(s.status(s.postKey()).path("hitCount").asLong()).isPositive();
        ObjectNode security = SecurityMcpSupport.generatedPlan(target.url(""));
        SecurityMcpSupport.packs(security, "web-api-core");
        SecurityMcpSupport.save(s.server, "plan", security);
        JsonNode sec = execute(s.server, "security_suite.execute_plan", suiteInput("security"), true).path("result");
        SecurityMcpSupport.completed(sec, "pass", 2);
        assertThat(target.received().uri()).isEqualTo("/safe");
        target.assertNoRequest();
    }

    private ObjectNode builtin(JvmProbeScenario s, String url) throws Exception {
        ObjectNode plan = plan("performance", url);
        ObjectNode contract = contract(plan);
        contract.withObject("loadModel").put("rampUpSeconds", 0);
        contract.putObject("workloadProvider").put("type", "builtin");
        contract.withObject("successCriteria").put("minThroughputPerSec", 0.000001).put("p95LatencyMs", 300000);
        ObjectNode entry = (ObjectNode) contract.path("entrypoints").get(0);
        entry.withObject("request").put("path", "/api/v1/posts/101").putObject("headers")
                .put("Authorization", "Bearer alice-token").put("x-run-as-user", "alice").put("x-run-as-tenant", "fixture-tenant");
        contract.putObject("observationTargets").put("probeBaseUrl", s.baseUrl).putArray("requiredLineHits").add(s.postKey());
        return plan;
    }

    private Map<String, Object> suiteInput(String suite) {
        return Map.of("projectName", PROJECT, "executionProfile", suite, "planName", "plan", "suiteRunId", "shared");
    }

    private void assertResult(McpServerProcess server, JvmProbeScenario s, String suite) throws Exception {
        JsonNode artifact = planRun(server, suite, "plan", "shared-1");
        var directory = McpServerSkillWorkflowIT.runFile(s.workspace, suite, "plan", "shared-1").getParent();
        assertThat(JSON.readTree(directory.resolve("execution.result.json").toFile())).isEqualTo(artifact);
        assertThat(JSON.readTree(directory.resolve("evidence.json").toFile()).isObject()).isTrue();
        assertThat(artifact.toString()).doesNotContain("alice-token");
        if (suite.equals("performance")) {
            assertThat(artifact.path("metrics")).isEqualTo(artifact.path("details").path("metrics"));
            assertThat(artifact.path("details").path("metrics").path("totalRequests").asInt()).isPositive();
            assertThat(artifact.path("details").path("requiredLineHits").get(0).asText()).isEqualTo(s.postKey());
        } else if (suite.equals("security")) {
            assertThat(artifact.path("coverage")).isEqualTo(artifact.path("details").path("coverage"));
            assertThat(artifact.path("findings")).isEqualTo(artifact.path("details").path("findings"));
            assertThat(artifact.path("details").path("coverage").path("complete").asBoolean()).isTrue();
            assertThat(artifact.path("details").path("coverage").path("plannedCount").asInt()).isEqualTo(2);
        } else {
            assertThat(artifact.path("steps")).isEqualTo(artifact.path("details").path("steps"));
            assertThat(artifact.path("details").path("steps").get(0).path("status").asText()).isEqualTo("pass");
        }
        assertThat(Files.size(directory.resolve("execution.result.json"))).isPositive();
    }
}
