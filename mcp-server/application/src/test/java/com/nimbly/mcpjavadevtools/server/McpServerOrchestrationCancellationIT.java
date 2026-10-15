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

@Tag("orchestration")
@Timeout(120)
class McpServerOrchestrationCancellationIT extends OrchestrationMcpSupport {
    @Test
    void cancellationDisconnectsActiveTransportAndKeepsCompletedPlanRecoverable() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget completed = TransportHttpTarget.start(); DelayedHttpTarget active = DelayedHttpTarget.start();
                McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, completed.url(""), "first", "cancel"));
            save(server, "first", generatedPlan(completed.url("/once")));
            ObjectNode cancelled = generatedPlan(active.url());
            http(step(cancelled, 0)).put("timeoutMs", 60000);
            save(server, "cancel", cancelled);
            assertStatus(orchestrate(server, PROFILE, "cancel", 1), "in_progress", "execution_plan_slice_complete");
            assertThat(completed.received().uri()).isEqualTo("/once");
            Path file = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/first/runs/cancel-1/execution.result.json");
            byte[] original = Files.readAllBytes(file);
            server.send(executeRequest(920, OPERATION, Map.of("projectName", PROJECT,
                    "executionProfile", PROFILE, "suiteRunId", "cancel", "maxPlansPerCall", 1), true));
            active.awaitRequest();
            active.assertStillConnected();
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 920)));
            active.awaitClientDisconnect();
            // Replace only the cancelled unfinished plan, never persisted successful state.
            save(server, "cancel", generatedPlan(completed.url("/recovered")));
            JsonNode result = orchestrate(server, PROFILE, "cancel", 1);
            assertStatus(result, "pass", "ok");
            assertThat(completed.received().uri()).isEqualTo("/recovered");
            completed.assertNoRequest();
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
            checkpoint(workspace, result);
            server.closeInputAndAwaitTermination();
            assertThat(server.hasResponseFor(920)).isFalse();
        }
    }

    @Test
    void boundedTransportTimeoutPersistsFailureAndReleasesRunLease() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (DelayedHttpTarget target = DelayedHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(), "timeout"));
            ObjectNode plan = generatedPlan(target.url());
            http(step(plan, 0)).put("timeoutMs", 150);
            save(server, "timeout", plan);
            JsonNode result = orchestrate(server, PROFILE, "timeout", 0);
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("fail");
            assertThat(result.path("details").path("planRuns").get(0).path("details").toString()).contains("transport_request_timeout");
            target.awaitClientDisconnect();
            checkpoint(workspace, result);
            JsonNode repeated = orchestrate(server, PROFILE, "timeout", 0);
            assertThat(repeated.path("status")).isEqualTo(result.path("status"));
            assertThat(repeated.path("reasonCode")).isEqualTo(result.path("reasonCode"));
            assertThat(repeated.path("details").path("planRuns")).hasSize(1);
        }
    }
}
