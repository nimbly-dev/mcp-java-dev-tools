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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("orchestration")
@Timeout(180)
class McpServerOrchestrationIT extends OrchestrationMcpSupport {
    @Test
    @Tag("orchestration-smoke")
    void orderedSlicesRestartFromRealArtifactsWithoutRepeatingCompletedRequests() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            JsonNode first;
            try (McpServerProcess server = start(workspace)) {
                ObjectNode project = context(workspace, target.url(""), "first", "second");
                saveContext(server, project);
                save(server, "first", generatedPlan(target.url("/first")));
                save(server, "second", generatedPlan(target.url("/second")));
                assertThat(execute(server, OPERATION, Map.of("projectName", PROJECT,
                        "executionProfile", PROFILE), false).path("status").asText()).isEqualTo("confirmation_required");
                target.assertNoRequest();
                first = orchestrate(server, PROFILE, "restart", 1);
                assertStatus(first, "in_progress", "execution_plan_slice_complete");
                assertThat(first.path("details").path("nextPlanOrder").asInt()).isEqualTo(2);
                assertThat(target.received().uri()).isEqualTo("/first");
                checkpoint(workspace, first);
                planRun(server, "regression", "first", "restart-1");
            }
            Path completed = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/first/runs/restart-1/execution.result.json");
            String original = Files.readString(completed);
            try (McpServerProcess server = start(workspace)) {
                JsonNode resumed = orchestrate(server, PROFILE, "restart", 1);
                assertStatus(resumed, "pass", "ok");
                assertThat(resumed.path("details").path("planRuns")).hasSize(2);
                assertThat(target.received().uri()).isEqualTo("/second");
                target.assertNoRequest();
                checkpoint(workspace, resumed);
                planRun(server, "regression", "second", "restart-2");
                assertStatus(orchestrate(server, PROFILE, "restart", 0), "pass", "ok");
                target.assertNoRequest();
                assertThat(Files.readString(completed)).isEqualTo(original);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"stop_on_fail,inherit,fail,1", "continue_on_fail,inherit,partial_fail,2",
            "stop_on_fail,continue,partial_fail,2", "continue_on_fail,stop,fail,1"})
    void failurePolicyControlsActualDownstreamEffects(String policy, String onFail, String status, int requests) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "bad", "after");
            profile(project).put("executionPolicy", policy);
            profile(project).withObject("providedContext").put("resourceId", "profile-baseline");
            ((ObjectNode) profile(project).path("plans").get(0)).put("onFail", onFail);
            ((ObjectNode) profile(project).path("plans").get(0)).putObject("providedContext").put("resourceId", "failed-producer");
            saveContext(server, project);
            ObjectNode bad = generatedPlan(target.url("/bad"));
            ((ObjectNode) step(bad, 0).path("expect").get(0)).put("expected", 201);
            save(server, "bad", bad);
            ObjectNode after = generatedPlan(target.url("/after"));
            http(step(after, 0)).putObject("headers").put("X-Resource", "${resourceId}");
            save(server, "after", after);
            JsonNode result = orchestrate(server, PROFILE, "policy", 0);
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo(status);
            assertThat(result.path("details").path("planRuns")).hasSize(requests);
            assertThat(target.received().uri()).isEqualTo("/bad");
            if (requests == 2) {
                var received = target.received();
                assertThat(received.uri()).isEqualTo("/after");
                assertThat(received.header("X-Resource")).isEqualTo("profile-baseline");
            }
            target.assertNoRequest();
            checkpoint(workspace, result);
            assertThat(orchestrate(server, PROFILE, "policy", 0).path("status").asText()).isEqualTo(status);
            target.assertNoRequest();
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/" + PROJECT
                    + "/plans/regression/bad/runs/policy-1/execution.result.json").toFile()).path("status").asText()).isEqualTo("fail");
        }
    }

    @Test
    void promotesOnlySafeSuccessfulContextAndResolvesPlanOverridesAndCredentials() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve("fixture.env"), "ORCHESTRATION_TOKEN=Bearer disposable-655-token\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "source", "consumer");
            root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                    .put("authorization", "ORCHESTRATION_TOKEN");
            profile(project).withObject("providedContext").put("runLabel", "profile-default");
            ((ObjectNode) profile(project).path("plans").get(0)).putObject("providedContext")
                    .put("resourceId", "fixture-655").put("runLabel", "plan-override").put("password", "discarded-655");
            saveContext(server, project);
            ObjectNode source = generatedPlan("${apiBaseUrl}/source");
            http(step(source, 0)).putObject("headers").put("X-Label", "${runLabel}");
            save(server, "source", source);
            ObjectNode consumer = generatedPlan("${apiBaseUrl}/consumer/${resourceId}");
            http(step(consumer, 0)).putObject("headers").put("Authorization", "${authorization}");
            save(server, "consumer", consumer);
            JsonNode result = orchestrate(server, PROFILE, "promotion", 0);
            assertStatus(result, "pass", "ok");
            assertThat(target.received().header("X-Label")).isEqualTo("plan-override");
            var received = target.received();
            assertThat(received.uri()).isEqualTo("/consumer/fixture-655");
            assertThat(received.header("Authorization")).isEqualTo("Bearer disposable-655-token");
            JsonNode persisted = checkpoint(workspace, result);
            assertThat(persisted.path("suiteContext").path("resourceId").asText()).isEqualTo("fixture-655");
            assertThat(persisted.toString()).doesNotContain("disposable-655-token", "discarded-655");
        }
    }

    @Test
    void generatedRunIdentityAndMissingArtifactsHaveDeterministicPersistedOutcomes() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "absent"));
            JsonNode result = orchestrate(server, PROFILE, null, 0);
            assertStatus(result, "fail", "plan_artifact_missing");
            assertThat(result.path("details").path("suiteRunId").asText()).startsWith("suite-");
            assertThat(checkpoint(workspace, result).path("planRuns").get(0).path("status").asText()).isEqualTo("blocked");
            target.assertNoRequest();
            assertStatus(orchestrate(server, "absent", "missing", 0), "blocked", "runtime_suite_missing");
        }
    }
}
