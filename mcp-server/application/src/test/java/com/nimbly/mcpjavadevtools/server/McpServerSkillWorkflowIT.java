package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cross-feature Tool/Artifact contracts; conversational Skill execution is a separate claim. */
@Tag("skill-workflows")
@Timeout(240)
class McpServerSkillWorkflowIT extends ReplayMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"ps1", "sh", "postman"})
    void independentWorkspacesResumeInspectMigrateAndReplay(String mode) throws Exception {
        Path first = JavaItScenario.workspace();
        Path second = JavaItScenario.workspace();
        AtomicInteger polls = new AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String state = exchange.getRequestURI().getPath().equals("/watch") && polls.incrementAndGet() < 2 ? "pending" : "ready";
            TransportHttpTarget.respond(exchange, 200, "{\"state\":\"" + state + "\"}");
        })) {
            prepareIndependentRuns(first, second, target);
            byte[] independent = Files.readAllBytes(runFile(second, "regression", "shared", "shared-1"));
            try (McpServerProcess one = start(first); McpServerProcess two = start(second)) {
                assertStatus(orchestrate(one, PROFILE, "shared", 1), "in_progress", "execution_plan_slice_complete");
                assertThat(target.received().uri()).isEqualTo("/watch");
                assertThat(target.received().uri()).isEqualTo("/external-one");
                JsonNode terminal = orchestrate(one, PROFILE, "shared", 1);
                assertStatus(terminal, "pass", "ok");
                assertThat(target.received().uri()).isEqualTo("/external-two");
                target.assertNoRequest();
                checkpoint(first, terminal);
                JsonNode persisted = assertRun(one, first, "shared", "shared", "pass");
                assertConsumers(one, first, persisted);
                migrate(one, first);
                assertThat(Files.readAllBytes(runFile(second, "regression", "shared", "shared-1"))).isEqualTo(independent);
                planRun(two, "regression", "shared", "shared-1");
                JsonNode separatePlan = ok(two, "regression_plan.read", Map.of("projectName", PROJECT, "planName", "shared",
                        "query", Map.of("select", List.of("contract"))));
                assertThat(separatePlan.path("artifact").path("contract").path("steps").get(0)
                        .path("transport").path("http").path("url").asText()).endsWith("/independent");
                replay(one, first, target, mode);
            }
        }
        JavaItScenario.observe("execution_orchestration.execute", "ASSERTED", "isolated_restart_consumers_migration_replay_" + mode);
    }

    private void prepareIndependentRuns(Path first, Path second, TransportHttpTarget target) throws Exception {
        try (McpServerProcess one = start(first); McpServerProcess two = start(second)) {
            saveContext(one, context(first, target.url(""), "shared"));
            saveContext(two, context(second, target.url(""), "shared"));
            save(one, "shared", McpServerRegressionRecoveryIT.resumable(target.url("")));
            save(two, "shared", generatedPlan(target.url("/independent")));
            assertStatus(orchestrate(one, PROFILE, "shared", 1), "in_progress", "execution_plan_slice_complete");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch");
            assertStatus(orchestrate(two, PROFILE, "shared", 1), "pass", "ok");
            assertThat(target.received().uri()).isEqualTo("/independent");
            assertRun(two, second, "shared", "shared", "pass");
        }
    }

    private void replay(McpServerProcess server, Path workspace, TransportHttpTarget target, String mode) throws Exception {
        // Replay the independent simple plan: resumable phase coverage was asserted above.
        save(server, "shared", generatedPlan(target.url("/replayed")));
        Path directory = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                "exportId", "workflow", "mode", mode, "includeRuntimeStartup", false, "includeHealthcheckGate", false));
        assertThat(packageText(directory)).doesNotContain(SECRET);
        runPackage(directory, replayCommand(mode, directory), Map.of());
        assertThat(target.received().uri()).isEqualTo("/replayed");
        target.assertNoRequest();
    }

    private void assertConsumers(McpServerProcess server, Path workspace, JsonNode persisted) throws Exception {
        Path directory = runFile(workspace, "regression", "shared", "shared-1").getParent();
        JsonNode evidence = JSON.readTree(directory.resolve("evidence.json").toFile());
        JsonNode details = persisted.path("details");
        assertThat(persisted.path("steps")).isEqualTo(details.path("steps"));
        assertThat(persisted.path("watchers")).isEqualTo(details.path("watchers"));
        assertThat(persisted.path("externalVerification")).isEqualTo(details.path("externalVerification"));
        assertThat(evidence.path("steps")).isEqualTo(details.path("steps"));
        assertThat(evidence.path("planRef").path("name").asText()).isEqualTo("shared");
        assertThat(details.path("steps")).isNotEmpty();
        assertThat(details.path("steps").get(0).path("durationMs").isNumber()).isTrue();
        assertThat(details.path("watchers").get(0).path("status").asText()).isEqualTo("pass");
        assertThat(details.path("externalVerification")).hasSize(2);
        assertThat(query(server, "watcher_state", Map.of("runId", "shared-1")).path("items")).hasSize(1);
        assertThat(query(server, "external_verification_state", Map.of("runId", "shared-1")).path("items")).hasSize(1);
        JsonNode summary = SkillWorkflowSupport.helper(workspace, "summarize-run", Map.of("status", persisted.path("status").asText(),
                "reasonCode", persisted.path("reasonCode").asText(), "checks", List.of("watchers=pass", "external=pass")));
        assertThat(summary.path("status").asText()).isEqualTo("pass");
        assertThat(summary.path("checks")).hasSize(2);
        JsonNode steps = SkillWorkflowSupport.helper(workspace, "step-execution-check", Map.of("steps", details.path("steps")));
        assertThat(steps.path("failedCount").asInt()).isZero();
        assertThat(steps.path("total").asInt()).isEqualTo(details.path("steps").size());
    }

    private void migrate(McpServerProcess server, Path workspace) throws Exception {
        Path project = workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json");
        byte[] original = Files.readAllBytes(project);
        ok(server, "project_context.read", Map.of("projectName", PROJECT, "query", Map.of("select", List.of("artifact"))));
        ok(server, "project_context.validate", Map.of("projectName", PROJECT));
        ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
        ok(server, "run_result.backfill", Map.of("projectName", PROJECT, "stateSurface", "correlation_state"));
        ok(server, "run_result.cutover", Map.of("projectName", PROJECT));
        ok(server, "run_result.cleanup", Map.of("projectName", PROJECT, "retention", Map.of("dryRun", true)));
        assertThat(query(server, "run_state", Map.of("runId", "shared-1")).path("items")).hasSize(1);
        assertThat(Files.readAllBytes(project)).as("Migration preserves project configuration").isEqualTo(original);
    }

    @Test
    @Tag("workflow-smoke")
    void inventoryMatchesPackagedCatalogAndEverySkillDependency() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            SkillWorkflowSupport.assertInventory(server, workspace);
        }
    }

    @Test
    void repositoryHelpersPreserveBlockedFailedAndCleanupBoundaries() throws Exception {
        Path workspace = JavaItScenario.workspace();
        JsonNode missing = SkillWorkflowSupport.helper(workspace, "preflight-resolve",
                Map.of("requiredKeys", List.of("apiBaseUrl"), "context", Map.of()));
        assertThat(missing.path("status").asText()).isEqualTo("needs_user_input");
        assertThat(missing.path("missing").get(0).asText()).isEqualTo("apiBaseUrl");
        JsonNode preflight = SkillWorkflowSupport.helper(workspace, "preflight-resolve",
                Map.of("requiredKeys", List.of("apiBaseUrl"), "context", Map.of("apiBaseUrl", "fixture")));
        assertThat(preflight.path("status").asText()).isEqualTo("ok");
        JsonNode runtime = SkillWorkflowSupport.helper(workspace, "runtime-converge",
                Map.of("autoStart", false, "apiReachable", true, "probeReachable", false));
        assertThat(runtime.path("reasonCode").asText()).isEqualTo("runtime_autostart_disabled_runtime_down");
        JsonNode gate = SkillWorkflowSupport.helper(workspace, "probe-gate-check",
                Map.of("probeVerification", true, "probeReachable", false));
        assertThat(gate.path("reasonCode").asText()).isEqualTo("probe_gate_failed");
        JsonNode failure = SkillWorkflowSupport.helper(workspace, "step-execution-check",
                Map.of("steps", List.of(Map.of("status", "fail"), Map.of("status", "pass"))));
        assertThat(failure.path("status").asText()).isEqualTo("fail");
        assertThat(failure.path("failedCount").asInt()).isEqualTo(1);
        JsonNode summary = SkillWorkflowSupport.helper(workspace, "summarize-run",
                Map.of("status", "blocked", "reasonCode", gate.path("reasonCode").asText()));
        assertThat(summary.path("status").asText()).isEqualTo("blocked");
        assertThat(summary.path("reasonCode").asText()).isEqualTo("probe_gate_failed");
        for (boolean autoStop : List.of(false, true)) {
            JsonNode cleanup = SkillWorkflowSupport.helper(workspace, "cleanup-runtime",
                    Map.of("startedByRun", List.of("owned-fixture-label"), "autoStopOnFinish", autoStop));
            assertThat(cleanup.path("stopped").size()).isEqualTo(autoStop ? 1 : 0);
        }
        // cleanup-runtime is a decision helper; real process shutdown is owned by JavaItScenario.
        JavaItScenario.observe("skill_helpers", "ASSERTED", "six_repository_node_helpers_no_runtime_cleanup_claim");
    }

    static Path runFile(Path workspace, String suite, String plan, String run) {
        return workspace.resolve(".mcpjvm/" + PROJECT + "/plans/" + suite + "/" + plan + "/runs/" + run + "/execution.result.json");
    }
}
