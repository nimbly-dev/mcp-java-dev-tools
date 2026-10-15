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

/** Investigation evidence becomes a durable regression guard without changing the application. */
@Tag("skill-workflows")
@Timeout(240)
class McpServerInvestigationRegressionWorkflowIT extends RegressionMcpSupport {
    @Test
    void reproducedExceptionBecomesAFailingGuardThatSurvivesRestartAndHealthyTraffic() throws Exception {
        Path source = repositoryRoot().resolve(JvmProbeScenario.SOURCE
                + FailureAnalysisScenario.SERVICE.replace('.', '/') + ".java");
        byte[] original = Files.readAllBytes(source);
        try (FailureAnalysisScenario s = FailureAnalysisScenario.start()) {
            s.attachAndRecord();
            assertThat(s.result("analyze_trace", s.analysis("guided")).path("outcome").asText()).isEqualTo("ANALYZED");
            s.trigger();
            JsonNode reproduced = s.result("verify_reproduction", s.verification());
            assertThat(reproduced.path("outcome").asText()).isEqualTo("REPRODUCED");
            assertThat(reproduced.path("attemptEvidence").path("captureId")).isEqualTo(s.capture.path("captureId"));
            String base = "http://127.0.0.1:" + s.runtime.target.port();
            saveContext(s.runtime.server, context(s.runtime.workspace, base, "rejection", "health"));
            save(s.runtime.server, "rejection", rejectionGuard(base));
            s.runtime.success("reset", Map.of("key", s.key));
            assertThat(run(s.runtime.server, "rejection", "investigated").path("reasonCode").asText())
                    .isEqualTo("transport_failed");
            assertThat(s.runtime.status(s.key).path("hitCount").asLong()).isPositive();
            assertRun(s.runtime.server, s.runtime.workspace, "rejection", "investigated", "fail");
            Path runFile = McpServerSkillWorkflowIT.runFile(s.runtime.workspace, "regression", "rejection", "investigated-1");
            byte[] failure = Files.readAllBytes(runFile);
            s.runtime.server.close();
            try (McpServerProcess restarted = start(s.runtime.workspace)) {
                assertDiagnosticEvidence(restarted, s.runtime.workspace, failure);
                save(restarted, "health", generatedPlan(base + "/actuator/health"));
                assertThat(run(restarted, "health", "healthy").path("details").path("runStatus").asText()).isEqualTo("pass");
                assertRun(restarted, s.runtime.workspace, "health", "healthy", "pass");
                assertThat(Files.readAllBytes(runFile)).as("Healthy traffic does not repair the failed behavior").isEqualTo(failure);
                assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(2);
                assertThat(Files.readAllBytes(source)).as("Investigation remains proposal-only").isEqualTo(original);
            }
            // The original MCP process is deliberately gone; cleanup uses a new connection to the same owned JVM.
            try (McpServerProcess cleanup = JvmProbeScenario.startServer(s.runtime.workspace)) {
                initialize(cleanup);
                JsonNode result = execute(cleanup, "jvm_lifecycle.deactivate", s.runtime.identity(), true).path("result");
                assertThat(result.path("reasonCode").asText()).isEqualTo("deactivated");
                assertThat(s.runtime.target.isHealthy()).isTrue();
            }
        }
        JavaItScenario.observe("failure_analysis.verify_reproduction", "ASSERTED", "real_exception_to_failed_guard_restart_independent_health");
    }

    private ObjectNode rejectionGuard(String base) {
        ObjectNode plan = generatedPlan(base + FailureAnalysisScenario.PATH);
        http(step(plan, 0)).put("method", "DELETE");
        // Explicit scenario expectation: a deliberate rejection should be 405. Do not change it to 500 to pass.
        ((ObjectNode) step(plan, 0).path("expect").get(0)).put("expected", 405);
        return plan;
    }

    private void assertDiagnosticEvidence(McpServerProcess server, Path workspace, byte[] original) throws Exception {
        JsonNode artifact = assertRun(server, workspace, "rejection", "investigated", "fail");
        assertThat(artifact.path("reasonCode").asText()).isEqualTo("transport_failed");
        assertThat(artifact.path("steps").get(0).path("status").asText()).isEqualTo("blocked");
        assertThat(artifact.path("steps").get(0).path("statusCode").asInt()).isEqualTo(500);
        JsonNode guard = ok(server, "regression_plan.read", Map.of("projectName", PROJECT, "planName", "rejection",
                "query", Map.of("select", List.of("contract"))));
        assertThat(guard.path("artifact").path("contract").path("steps").get(0).path("expect").get(0)
                .path("expected").asInt()).isEqualTo(405);
        JsonNode read = ok(server, "run_result.read", Map.of("projectName", PROJECT,
                "planName", "rejection", "runId", "investigated-1"));
        assertThat(read.path("evidence").path("steps")).isEqualTo(artifact.path("steps"));
        assertThat(read.path("artifact")).isEqualTo(JSON.readTree(original));
        assertThat(read.toString()).doesNotContain("alice-token");
        assertThat(query(server, "run_state", Map.of("runId", "investigated-1")).path("items").get(0)
                .path("status").asText()).isEqualTo("fail");
    }
}
