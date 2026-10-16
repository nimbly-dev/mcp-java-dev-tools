package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.nio.file.ExtendedOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Failed publication and mixed run envelopes preserve coherent evidence through the public MCP boundary. */
@Tag("artifact")
@Tag("skill-workflows")
@Timeout(120)
class McpServerRunArtifactRecoveryIT extends ArtifactMcpSupport {
    @ParameterizedTest
    @CsvSource({"continue_on_fail,inherit", "stop_on_fail,continue"})
    void orderedRecoveryFailureStopsLaterPlansRegardlessOfFailurePolicy(String policy, String onFail) throws Exception {
        Path workspace = JavaItScenario.workspace();
        Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", "regression", "suiteRunId", "barrier");
        Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/barrier-1");
        Map<String, byte[]> before;
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            try (McpServerProcess server = start(workspace)) {
                saveOrderedRecoveryPlans(server, workspace, target, policy, onFail, false);
                try (var channel = FileChannel.open(workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite.lock"),
                        StandardOpenOption.WRITE); var lock = channel.lock()) {
                    assertThat(execute(server, "execution_orchestration.execute", input, true).path("result").path("status").asText())
                            .isNotEqualTo("pass");
                    assertThat(target.received().uri()).isEqualTo("/once");
                    target.assertNoRequest();
                    before = snapshot(directory, List.of("context.resolved.json", "evidence.json", "execution.result.json"));
                    JsonNode retry = execute(server, "execution_orchestration.execute", input, true).path("result");
                    assertThat(retry.path("status").asText()).isEqualTo("blocked");
                    assertThat(retry.path("reasonCode").asText()).isEqualTo("execution_run_artifact_persist_failed");
                    assertThat(retry.path("reasonMeta").path("projectionReasonCode").asText()).isEqualTo("state_store_busy");
                    target.assertNoRequest();
                    assertSnapshot(directory, before);
                }
            }
            try (McpServerProcess restarted = start(workspace)) {
                assertThat(execute(restarted, "execution_orchestration.execute", input, true).path("result").path("status").asText())
                        .isEqualTo("pass");
                assertThat(target.received().uri()).isEqualTo("/later");
                target.assertNoRequest();
                assertSnapshot(directory, before);
                assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(2);
            }
        }
    }

    @Test
    void orderedActiveWatcherIsRecoveredBeforeResumeAndNeverRepeatsItsTrigger() throws Exception {
        Path workspace = JavaItScenario.workspace();
        AtomicInteger polls = new AtomicInteger();
        Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", "regression", "suiteRunId", "active",
                "maxPlansPerCall", 1);
        Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/active-1");
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String state = exchange.getRequestURI().getPath().equals("/watch") && polls.incrementAndGet() == 1 ? "pending" : "ready";
            TransportHttpTarget.respond(exchange, 200, "{\"state\":\"" + state + "\"}");
        })) {
            try (McpServerProcess server = start(workspace)) {
                saveOrderedRecoveryPlans(server, workspace, target, "continue_on_fail", "inherit", true);
                try (var channel = FileChannel.open(workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite.lock"),
                        StandardOpenOption.WRITE); var lock = channel.lock()) {
                    assertThat(execute(server, "execution_orchestration.execute", input, true).path("result").path("status").asText())
                            .isNotEqualTo("pass");
                    assertThat(target.received().uri()).isEqualTo("/trigger");
                    assertThat(target.received().uri()).isEqualTo("/watch");
                    Map<String, byte[]> before = snapshot(directory, List.of("context.resolved.json", "evidence.json", "execution.result.json"));
                    assertThat(JSON.readTree(directory.resolve("execution.result.json").toFile()).path("status").asText()).isEqualTo("in_progress");
                    JsonNode retry = execute(server, "execution_orchestration.execute", input, true).path("result");
                    assertThat(retry.path("reasonCode").asText()).isEqualTo("execution_run_artifact_persist_failed");
                    target.assertNoRequest();
                    assertThat(polls).hasValue(1);
                    assertSnapshot(directory, before);
                }
            }
            try (McpServerProcess restarted = start(workspace)) {
                assertThat(execute(restarted, "execution_orchestration.execute", input, true).path("result").path("status").asText())
                        .isEqualTo("in_progress");
                assertThat(target.received().uri()).isEqualTo("/watch");
                target.assertNoRequest();
                assertThat(polls).hasValue(2);
                assertThat(execute(restarted, "execution_orchestration.execute", input, true).path("result").path("status").asText())
                        .isEqualTo("pass");
                assertThat(target.received().uri()).isEqualTo("/later");
                target.assertNoRequest();
                assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(2);
            }
        }
    }

    private void saveOrderedRecoveryPlans(McpServerProcess server, Path workspace, TransportHttpTarget target,
            String policy, String onFail, boolean active) throws Exception {
        ObjectNode project = RegressionMcpSupport.context(workspace, target.url(""), "plan", "later");
        ObjectNode profile = (ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0);
        profile.put("executionPolicy", policy);
        ((ObjectNode) profile.path("plans").get(0)).put("onFail", onFail);
        RegressionMcpSupport.saveContext(server, project);
        ObjectNode plan = active ? McpServerRegressionRecoveryIT.resumable(target.url(""))
                : RegressionMcpSupport.generatedPlan(target.url("/once"));
        if (active) { RegressionMcpSupport.contract(plan).remove("externalVerification"); }
        RegressionMcpSupport.save(server, "plan", plan);
        RegressionMcpSupport.save(server, "later", RegressionMcpSupport.generatedPlan(target.url("/later")));
    }

    private void assertSnapshot(Path directory, Map<String, byte[]> before) throws Exception {
        for (var file : before.entrySet()) {
            assertThat(Files.readAllBytes(directory.resolve(file.getKey()))).isEqualTo(file.getValue());
        }
    }

    @Test
    void supportedResultLargerThanProjectionBoundKeepsCanonicalEvidenceAndQueryableSummary() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ObjectNode payload = JSON.createObjectNode().put("status", "pass");
            var steps = payload.putArray("steps");
            for (int index = 0; index < 24; index++) {
                steps.addObject().put("id", "step-" + index).put("status", "pass")
                        .putArray("assertions").addObject().put("expected", "x".repeat(3000));
            }
            ok(server, "run_result.upsert", Map.of("projectName", PROJECT, "planName", "plan", "runId", "large", "payload", payload));
            Path canonical = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/large/execution.result.json");
            assertThat(Files.size(canonical)).isGreaterThan(64 * 1024);
            assertThat(JSON.readTree(canonical.toFile())).isEqualTo(payload);
            JsonNode row = query(server, "run_state", Map.of("runId", "large")).path("items").get(0);
            assertThat(row.path("status").asText()).isEqualTo("pass");
            assertThat(row.path("projection").path("projectionStatus").asText()).isEqualTo("artifact_only");
            assertThat(row.path("projection").path("sourcePathRel").asText()).endsWith("/runs/large");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordered", "direct"})
    void lockedProjectionRetryAndRestartNeverRepeatTheHttpRequest(String mode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            Map<String, Object> input = new LinkedHashMap<>(RegressionMcpSupport.selector("plan", "recovery"));
            if (mode.equals("ordered")) {
                input.remove("planName");
            }
            String operation = mode.equals("direct") ? "regression_suite.execute_plan" : "execution_orchestration.execute";
            Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/recovery-1");
            Map<String, byte[]> before;
            try (McpServerProcess server = start(workspace)) {
                RegressionMcpSupport.saveContext(server, RegressionMcpSupport.context(workspace, target.url(""), "plan"));
                RegressionMcpSupport.save(server, "plan", RegressionMcpSupport.generatedPlan(target.url("/once")));
                Path lockFile = workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite.lock");
                try (var channel = FileChannel.open(lockFile, StandardOpenOption.WRITE); var lock = channel.lock()) {
                    assertThat(execute(server, operation, input, true).path("result").path("status").asText())
                            .isNotIn("pass", "ready");
                    assertThat(target.received().uri()).isEqualTo("/once");
                    before = snapshot(directory, List.of("context.resolved.json", "evidence.json", "execution.result.json"));
                    assertThat(execute(server, operation, input, true).path("result").path("status").asText())
                            .isNotIn("pass", "ready");
                    target.assertNoRequest();
                }
            }
            try (McpServerProcess restarted = start(workspace)) {
                assertThat(execute(restarted, operation, input, true).path("result").path("status").asText()).isIn("pass", "ready");
                target.assertNoRequest();
                JsonNode rows = query(restarted, "run_state", Map.of("runId", "recovery-1")).path("items");
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).path("status").asText()).isEqualTo("pass");
                for (var file : before.entrySet()) {
                    assertThat(Files.readAllBytes(directory.resolve(file.getKey()))).isEqualTo(file.getValue());
                }
            }
        }
    }
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failedCanonicalMoveRollsBackCompanionsBeforeReadAndRetry() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ObjectNode payload = JSON.createObjectNode().put("status", "pass");
            payload.putObject("details").put("summary", "original");
            payload.putObject("correlation").put("correlationSessionId", "original");
            Map<String, Object> input = Map.of("projectName", PROJECT, "planName", "plan", "runId", "run", "payload", payload);
            ok(server, "run_result.upsert", input);
            Path run = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/run");
            Map<String, byte[]> before = snapshot(run);
            payload.put("status", "fail");
            payload.withObject("details").put("summary", "replacement");
            payload.withObject("correlation").put("correlationSessionId", "replacement");
            try (var held = FileChannel.open(run.resolve("execution.result.json"),
                    Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
                assertThat(held.isOpen()).isTrue();
                blocked(server, "run_result.upsert", input, "artifact_write_failed");
                for (var file : before.entrySet()) {
                    assertThat(Files.readAllBytes(run.resolve(file.getKey()))).as(file.getKey()).isEqualTo(file.getValue());
                }
                JsonNode read = ok(server, "run_result.read", runInput("run"));
                assertThat(read.path("artifact").path("status").asText()).isEqualTo("pass");
                assertThat(read.path("evidence").path("status").asText()).isEqualTo("pass");
            }
            ok(server, "run_result.upsert", input);
            JsonNode read = ok(server, "run_result.read", runInput("run"));
            assertThat(read.path("artifact").path("status").asText()).isEqualTo("fail");
            assertThat(read.path("evidence").path("status").asText()).isEqualTo("fail");
        }
        JavaItScenario.observe("artifact_management.run_result.upsert", "ASSERTED", "real_last_move_failure_rollback_read_retry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nested", "root", "duplicate", "direct"})
    void largeMixedShapeEvidenceRemainsReadableAndProjectsOnceAfterRestart(String shape) throws Exception {
        Path workspace = JavaItScenario.workspace();
        ObjectNode payload = payload(shape);
        Path canonical = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/run/execution.result.json");
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ok(server, "run_result.upsert", Map.of("projectName", PROJECT, "planName", "plan", "runId", "run", "payload", payload));
            assertEvidence(server, payload);
        }
        byte[] original = Files.readAllBytes(canonical);
        try (McpServerProcess restarted = start(workspace)) {
            assertEvidence(restarted, payload);
            ok(restarted, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
            JsonNode watchers = payload.path("watchers").isArray() ? payload.path("watchers") : payload.path("details").path("watchers");
            assertThat(query(restarted, "watcher_state", Map.of("runId", "run")).path("items").get(0).path("state"))
                    .isEqualTo(watchers);
            assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(1);
            assertThat(Files.readAllBytes(canonical)).isEqualTo(original);
        }
        JavaItScenario.observe("artifact_management.run_result.rebuild", "ASSERTED", "large_evidence_once_" + shape);
    }

    private ObjectNode payload(String shape) {
        ObjectNode payload = JSON.createObjectNode().put("status", "blocked");
        ObjectNode details = payload.putObject("details").put("summary", "retained metadata");
        var watchers = details.putArray("watchers");
        for (int index = 0; index < 14; index++) {
            watchers.addObject().put("id", "watcher-" + index).put("status", "blocked").put("evidence", "x".repeat(2800));
        }
        if (!shape.equals("nested")) {
            payload.set("watchers", watchers.deepCopy());
        }
        if (shape.equals("root")) {
            details.remove("watchers");
        } else if (shape.equals("direct")) {
            payload.putObject("directResult").set("details", details.deepCopy());
        }
        payload.putArray("steps").addObject().put("id", "root-step").put("status", "blocked");
        return payload;
    }

    private void assertEvidence(McpServerProcess server, ObjectNode expected) throws Exception {
        JsonNode read = ok(server, "run_result.read", runInput("run"));
        assertThat(read.path("artifact")).isEqualTo(expected);
        assertThat(read.path("evidence").path("steps")).isEqualTo(expected.path("steps"));
        assertThat(read.path("evidence").path("summary").asText()).isEqualTo("retained metadata");
    }

    private Map<String, byte[]> snapshot(Path directory) throws Exception {
        return snapshot(directory, List.of("correlation.json", "context.resolved.json", "evidence.json", "execution.result.json"));
    }

    private Map<String, byte[]> snapshot(Path directory, List<String> names) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, Files.readAllBytes(directory.resolve(name)));
        }
        return result;
    }
}
