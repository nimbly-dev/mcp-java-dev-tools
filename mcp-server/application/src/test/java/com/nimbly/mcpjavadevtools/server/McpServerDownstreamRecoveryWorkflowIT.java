package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Downstream repair starts a new run while the original failure remains diagnosable. */
@Tag("skill-workflows")
@Timeout(180)
class McpServerDownstreamRecoveryWorkflowIT extends OrchestrationMcpSupport {
    @ParameterizedTest
    @CsvSource({"watcher,watcher_expectation_failed,watcher_state", "external,external_verification_expectation_failed,external_verification_state"})
    void restartRetainsTerminalFailureAndARepairedDownstreamRequiresANewRun(String phase, String reason, String surface) throws Exception {
        Path workspace = JavaItScenario.workspace();
        AtomicBoolean ready = new AtomicBoolean();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, ready.get() ? "{\"state\":\"ready\"}" : "{\"state\":\"broken\"}"))) {
            try (McpServerProcess server = start(workspace)) {
                saveContext(server, context(workspace, target.url(""), "completion"));
                save(server, "completion", completionPlan(target, phase));
                JsonNode failed = orchestrate(server, PROFILE, "failed", 0);
                assertThat(failed.path("status").asText()).isEqualTo("fail");
                checkpoint(workspace, failed);
                assertThat(assertRun(server, workspace, "completion", "failed", "fail").path("reasonCode").asText()).isEqualTo(reason);
                assertRequests(target, phase);
            }
            Path failurePath = McpServerSkillWorkflowIT.runFile(workspace, "regression", "completion", "failed-1");
            byte[] failure = Files.readAllBytes(failurePath);
            ready.set(true); // Repair the actual controlled downstream, preserving the unchanged plan and assertions.
            try (McpServerProcess server = start(workspace)) {
                JsonNode diagnostic = query(server, surface, Map.of("runId", "failed-1")).path("items");
                assertThat(diagnostic).hasSize(1);
                assertThat(diagnostic.get(0).path("state").get(0).path("reasonCode").asText()).isEqualTo(reason);
                assertThat(orchestrate(server, PROFILE, "failed", 0).path("status").asText()).isEqualTo("fail");
                target.assertNoRequest();
                JsonNode recovered = orchestrate(server, PROFILE, "recovered", 0);
                assertStatus(recovered, "pass", "ok");
                checkpoint(workspace, recovered);
                assertRequests(target, phase);
                assertRun(server, workspace, "completion", "recovered", "pass");
                assertThat(Files.readAllBytes(failurePath)).isEqualTo(failure);
                assertThat(query(server, "run_state", Map.of()).path("items")).hasSize(2);
                JsonNode read = ok(server, "run_result.read", Map.of("projectName", PROJECT, "planName", "completion", "runId", "failed-1"));
                assertThat(read.path("evidence").path("status").asText()).isEqualTo("fail");
            }
        }
        JavaItScenario.observe(OPERATION, "ASSERTED", "downstream_" + phase + "_repair_preserves_failed_run_and_diagnostics");
    }

    private ObjectNode completionPlan(TransportHttpTarget target, String phase) {
        ObjectNode plan = generatedPlan(target.url("/trigger"));
        if (phase.equals("watcher")) {
            McpServerRegressionVerificationIT.watcher(plan, target.url("/watch"))
                    .withObject("waitPolicy").put("retryMax", 1);
        } else {
            McpServerRegressionVerificationIT.external(plan, target.url("/verify"));
        }
        return plan;
    }

    private void assertRequests(TransportHttpTarget target, String phase) throws Exception {
        assertThat(target.received().uri()).isEqualTo("/trigger");
        assertThat(target.received().uri()).isEqualTo(phase.equals("watcher") ? "/watch" : "/verify");
        target.assertNoRequest();
    }
}
