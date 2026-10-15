package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("orchestration")
@Timeout(180)
class McpServerOrchestrationRecoveryIT extends OrchestrationMcpSupport {
    @Test
    void watcherAndExternalContinuationSurviveRestartWithoutRepeatingTriggers() throws Exception {
        Path workspace = JavaItScenario.workspace();
        AtomicInteger polls = new AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String state = exchange.getRequestURI().getPath().equals("/watch") && polls.incrementAndGet() < 2 ? "pending" : "ready";
            TransportHttpTarget.respond(exchange, 200, "{\"state\":\"" + state + "\"}");
        })) {
            try (McpServerProcess server = start(workspace)) {
                saveContext(server, context(workspace, target.url(""), "resume"));
                save(server, "resume", McpServerRegressionRecoveryIT.resumable(target.url("")));
                JsonNode result = orchestrate(server, PROFILE, "resume", 1);
                assertStatus(result, "in_progress", "execution_plan_slice_complete");
                assertThat(result.path("details").path("progress").path("activePlan").path("phase").asText()).isEqualTo("watchers");
                assertThat(target.received().uri()).isEqualTo("/trigger");
                assertThat(target.received().uri()).isEqualTo("/watch");
                checkpoint(workspace, result);
            }
            try (McpServerProcess server = start(workspace)) {
                JsonNode next = orchestrate(server, PROFILE, "resume", 1);
                assertStatus(next, "in_progress", "execution_plan_slice_complete");
                assertThat(target.received().uri()).isEqualTo("/watch");
                assertThat(target.received().uri()).isEqualTo("/external-one");
                JsonNode last = orchestrate(server, PROFILE, "resume", 1);
                assertStatus(last, "pass", "ok");
                assertThat(target.received().uri()).isEqualTo("/external-two");
                target.assertNoRequest();
                checkpoint(workspace, last);
                planRun(server, "regression", "resume", "resume-1");
            }
        }
    }

    @Test
    void realFileLeaseRejectsAnotherMcpProcessThenReleasesForReuse() throws Exception {
        Path workspace = JavaItScenario.workspace();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            entered.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                TransportHttpTarget.respond(exchange, 200, "{}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }); McpServerProcess first = start(workspace); McpServerProcess second = start(workspace)) {
            saveContext(first, context(workspace, target.url(""), "lease"));
            save(first, "lease", generatedPlan(target.url("/lease")));
            first.send(executeRequest(900, OPERATION, Map.of("projectName", PROJECT,
                    "executionProfile", PROFILE, "suiteRunId", "lease"), true));
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                assertStatus(orchestrate(second, PROFILE, "lease", 0), "blocked", "execution_suite_run_active");
            } finally {
                release.countDown();
            }
            assertStatus(toolPayload(first.responseFor(900, Duration.ofSeconds(30))).path("result"), "pass", "ok");
            assertThat(target.received().uri()).isEqualTo("/lease");
            assertStatus(orchestrate(second, PROFILE, "lease", 0), "pass", "ok");
            target.assertNoRequest();
        }
    }

    @Test
    void changedProfileCannotReuseAnExistingRunIdentity() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "first", "second");
            saveContext(server, project);
            save(server, "first", generatedPlan(target.url("/first")));
            save(server, "second", generatedPlan(target.url("/second")));
            assertStatus(orchestrate(server, PROFILE, "immutable", 1), "in_progress", "execution_plan_slice_complete");
            assertThat(target.received().uri()).isEqualTo("/first");
            profile(project).withObject("providedContext").put("runLabel", "changed");
            saveContext(server, project);
            assertStatus(orchestrate(server, PROFILE, "immutable", 1), "blocked", "execution_suite_checkpoint_definition_mismatch");
            profile(project).put("executionProfile", "other");
            saveContext(server, project);
            assertStatus(orchestrate(server, "other", "immutable", 1), "blocked", "execution_suite_checkpoint_context_mismatch");
            target.assertNoRequest();
        }
    }
}
