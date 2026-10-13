package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("regression")
@Timeout(180)
class McpServerRegressionRecoveryIT extends RegressionMcpSupport {
    @Test
    void shortSecretDoesNotAlterCheckpointControlsOrEscapeOnResume() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"state\":\"pending\",\"value\":\"a\"}"));
                McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "checkpoint"));
            ObjectNode plan = resumable(target.url(""));
            step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.value").put("as", "value").put("secret", true);
            save(server, "checkpoint", plan);
            JsonNode first = run(server, "checkpoint", "checkpoint");
            assertWaiting(first, "watchers");
            String fingerprint = first.path("details").path("continuation").path("fingerprint").asText();
            assertThat(fingerprint).matches("[a-f0-9]{64}");
            JsonNode resumed = run(server, "checkpoint", "checkpoint");
            assertWaiting(resumed, "watchers");
            assertThat(resumed.path("details").path("continuation").path("fingerprint").asText()).isEqualTo(fingerprint);
            assertThat(resumed.path("details").path("watchers").get(0).path("response").path("bodyJson").asText()).isEqualTo("[REDACTED]");
            assertThat(resumed.path("details").path("continuation").path("context").has("value")).isFalse();
            assertRun(server, workspace, "checkpoint", "checkpoint", "in_progress");
        }
    }

    @Test
    void blockedPrerequisiteCanBeProvisionedBeforeTriggerAndDefaultsReachTheRequest() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "prerequisite", "default");
            saveContext(server, project);
            ObjectNode plan = generatedPlan(target.url("/provisioned"));
            contract(plan).putArray("prerequisites").addObject().put("key", "requiredValue")
                    .put("required", true).put("secret", false).put("provisioning", "user_input");
            http(step(plan, 0)).putObject("headers").put("X-Provided", "${requiredValue}");
            save(server, "prerequisite", plan);
            assertThat(run(server, "prerequisite", "prerequisite").path("status").asText()).isEqualTo("needs_user_input");
            target.assertNoRequest();
            ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0).path("providedContext"))
                    .put("requiredValue", "provisioned");
            saveContext(server, project);
            assertThat(run(server, "prerequisite", "prerequisite").path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(target.received().header("X-Provided")).isEqualTo("provisioned");
            assertRun(server, workspace, "prerequisite", "prerequisite", "pass");
            ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0).path("providedContext")).remove("requiredValue");
            saveContext(server, project);
            ((ObjectNode) contract(plan).path("prerequisites").get(0)).put("default", "fallback");
            save(server, "default", plan);
            assertThat(run(server, "default", "default").path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(target.received().header("X-Provided")).isEqualTo("fallback");
        }
    }

    @Test
    void rotatedProjectCredentialsAreReloadedOnResumeAfterRestart() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve("fixture.env"), "FIXTURE_AUTH=Bearer first-value\n");
        AtomicInteger polls = new AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String state = exchange.getRequestURI().getPath().equals("/watch") && polls.incrementAndGet() < 2 ? "pending" : "ready";
            TransportHttpTarget.respond(exchange, 200, "{\"state\":\"" + state + "\"}");
        })) {
            try (McpServerProcess server = start(workspace)) {
                ObjectNode project = context(workspace, target.url(""), "rotation");
                ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
                root.put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                        .put("authorization", "FIXTURE_AUTH");
                saveContext(server, project);
                ObjectNode plan = resumable(target.url(""));
                contract(plan).remove("externalVerification");
                ((ObjectNode) contract(plan).path("watchers").get(0).path("provider").path("transport").path("request"))
                        .putObject("headers").put("Authorization", "${authorization}");
                save(server, "rotation", plan);
                assertWaiting(run(server, "rotation", "rotate"), "watchers");
                assertThat(target.received().uri()).isEqualTo("/trigger");
                assertThat(target.received().header("Authorization")).isEqualTo("Bearer first-value");
            }
            Files.writeString(workspace.resolve("fixture.env"), "FIXTURE_AUTH=Bearer second-value\n");
            try (McpServerProcess server = start(workspace)) {
                JsonNode result = run(server, "rotation", "rotate");
                assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
                assertThat(target.received().header("Authorization")).isEqualTo("Bearer second-value");
                target.assertNoRequest();
                JsonNode persisted = assertRun(server, workspace, "rotation", "rotate", "pass");
                assertThat(persisted.toString()).doesNotContain("first-value", "second-value");
            }
        }
    }

    @Test
    void directWatcherAndExternalPhasesResumeAfterRestartWithoutResendingTriggers() throws Exception {
        Path workspace = JavaItScenario.workspace();
        AtomicInteger polls = new AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String body = exchange.getRequestURI().getPath().equals("/watch") && polls.incrementAndGet() < 2
                    ? "{\"state\":\"pending\"}" : "{\"state\":\"ready\"}";
            TransportHttpTarget.respond(exchange, 200, body);
        })) {
            try (McpServerProcess server = start(workspace)) {
                saveContext(server, context(workspace, target.url(""), "resume"));
                save(server, "resume", resumable(target.url("")));
                JsonNode result = run(server, "resume", "restart");
                assertWaiting(result, "watchers");
                assertRun(server, workspace, "resume", "restart", "in_progress");
                assertThat(target.received().uri()).isEqualTo("/trigger");
                assertThat(target.received().uri()).isEqualTo("/watch");
                target.assertNoRequest();
            }
            try (McpServerProcess server = start(workspace)) {
                JsonNode result = run(server, "resume", "restart");
                assertWaiting(result, "external_verification");
                assertThat(result.path("details").path("watchers").get(0).path("attemptCount").asInt()).isEqualTo(2);
                assertThat(target.received().uri()).isEqualTo("/watch");
                assertThat(target.received().uri()).isEqualTo("/external-one");
                target.assertNoRequest();
            }
            try (McpServerProcess server = start(workspace)) {
                JsonNode result = run(server, "resume", "restart");
                assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
                assertThat(result.path("details").path("externalVerification")).hasSize(2);
                assertThat(target.received().uri()).isEqualTo("/external-two");
                assertRun(server, workspace, "resume", "restart", "pass");
                assertThat(run(server, "resume", "restart")).isEqualTo(result);
                target.assertNoRequest();
            }
        }
    }

    @Test
    void orchestratedContinuationKeepsTheSameActivePlanAndRejectsChangedContract() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Map<String, Object> selector = Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                "suiteRunId", "orchestrated", "maxPlansPerCall", 1);
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "resume"));
            ObjectNode plan = resumable(target.url(""));
            save(server, "resume", plan);
            JsonNode result = execute(server, "execution_orchestration.execute", selector, true).path("result");
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("in_progress");
            assertThat(result.path("details").path("nextPlanOrder").asInt()).isEqualTo(1);
            assertThat(result.path("details").path("progressSummary").path("activePlan").path("phase").asText()).isEqualTo("watchers");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch");
            http(step(plan, 0)).put("url", target.url("/changed-must-not-trigger"));
            save(server, "resume", plan);
            JsonNode changed = execute(server, "execution_orchestration.execute", selector, true).path("result");
            assertThat(changed.path("status").asText()).isEqualTo("fail");
            assertThat(changed.path("reasonCode").asText()).isEqualTo("regression_continuation_conflict");
            target.assertNoRequest();
        }
    }

    @Test
    void waitingDeadlineSurvivesResumesAndTerminatesWithoutAnExtraPoll() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "timeout"));
            ObjectNode plan = resumable(target.url(""));
            ((ObjectNode) contract(plan).path("watchers").get(0).path("waitPolicy")).put("timeoutMs", 200).put("intervalMs", 1);
            save(server, "timeout", plan);
            JsonNode first = run(server, "timeout", "deadline");
            assertWaiting(first, "watchers");
            long deadline = first.path("details").path("watchers").get(0).path("deadlineEpochMs").asLong();
            assertThat(deadline).isPositive();
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch");
            Thread.sleep(250);
            JsonNode timedOut = run(server, "timeout", "deadline");
            assertThat(timedOut.path("reasonCode").asText()).isEqualTo("watcher_timeout");
            assertThat(timedOut.path("details").path("watchers").get(0).path("attemptCount").asInt()).isEqualTo(1);
            target.assertNoRequest();
            assertRun(server, workspace, "timeout", "deadline", "fail");
        }
    }

    static ObjectNode resumable(String url) {
        ObjectNode plan = generatedPlan(url + "/trigger");
        ((ObjectNode) plan.path("metadata").path("execution")).put("verificationBatchSize", 1);
        ObjectNode watcher = McpServerRegressionVerificationIT.watcher(plan, url + "/watch");
        ((ObjectNode) watcher.path("waitPolicy")).put("timeoutMs", 30000).put("retryMax", 10);
        McpServerRegressionVerificationIT.external(plan, url + "/external-one");
        McpServerRegressionVerificationIT.external(plan, url + "/external-two").put("id", "second");
        return plan;
    }

    static void assertWaiting(JsonNode result, String phase) {
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("in_progress");
        assertThat(result.path("details").path("continuation").path("phase").asText()).isEqualTo(phase);
        assertThat(result.path("details").path("steps")).hasSize(1);
        assertThat(result.path("details").path("runStatus").asText()).isEqualTo("in_progress");
    }
}
