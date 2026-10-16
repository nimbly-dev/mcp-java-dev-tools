package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("artifact")
@Timeout(180)
class McpServerArtifactStateIT extends ArtifactMcpSupport {
    @Test
    void realSuiteContinuationSurvivesServerRestartWithoutRepeatingCompletedPlan() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path fixtureJar = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/user-service/user-app/target/user-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixtureJar).as("Spring fixture prerequisite").isRegularFile();
        Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", "regression", "suiteRunId", "resume", "maxPlansPerCall", 1);
        Path checkpoint = workspace.resolve(".mcpjvm/" + PROJECT + "/suite-runs/resume/execution_orchestration.result.json");
        Path firstResult = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/resume-1/execution.result.json");
        try (SpringFixtureTargetProcess fixture = SpringFixtureTargetProcess.start(fixtureJar)) {
            fixture.awaitHealthy();
            byte[] completed;
            try (McpServerProcess server = start(workspace)) {
                ObjectNode project = project(workspace);
                ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0))
                        .withArray("plans").addObject().put("order", 2).put("planName", "second");
                ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
                ObjectNode payload = verifiedPlan("http://127.0.0.1:" + fixture.port() + "/actuator/health");
                ok(server, "regression_plan.upsert", planInput("regression", payload));
                ok(server, "regression_plan.upsert", Map.of("projectName", PROJECT, "planName", "second", "payload", payload));
                JsonNode slice = execute(server, "execution_orchestration.execute", input, true);
                assertThat(slice.path("result").path("status").asText()).isEqualTo("in_progress");
                JsonNode saved = JSON.readTree(checkpoint.toFile());
                assertThat(saved.path("status").asText()).isEqualTo("in_progress");
                assertThat(saved.path("nextPlanOrder").asInt()).isEqualTo(2);
                completed = Files.readAllBytes(firstResult);
                assertThat(JSON.readTree(completed).path("status").asText()).isEqualTo("pass");
                assertContinuationProjection(server, saved);
            }
            try (McpServerProcess restarted = start(workspace)) {
                assertContinuationProjection(restarted, JSON.readTree(checkpoint.toFile()));
                assertThat(execute(restarted, "execution_orchestration.execute", input, true).path("result").path("status").asText()).isEqualTo("pass");
                assertThat(Files.readAllBytes(firstResult)).isEqualTo(completed);
                JsonNode saved = JSON.readTree(checkpoint.toFile());
                assertThat(saved.path("status").asText()).isEqualTo("pass");
                assertThat(saved.path("planRuns")).hasSize(2);
                assertThat(saved.path("planRuns")).extracting(run -> run.path("status").asText()).containsExactly("pass", "ready");
                assertThat(saved.path("planRuns")).allSatisfy(run -> assertThat(run.path("details").path("runStatus").asText()).isEqualTo("pass"));
                assertThat(ok(restarted, "run_result.read", Map.of("projectName", PROJECT, "planName", "second", "runId", "resume-2"))
                        .path("artifact").path("status").asText()).isEqualTo("pass");
                assertContinuationProjection(restarted, saved);
                assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(2);
            }
            try (McpServerProcess restarted = start(workspace)) {
                assertContinuationProjection(restarted, JSON.readTree(checkpoint.toFile()));
                assertThat(Files.readAllBytes(firstResult)).isEqualTo(completed);
            }
        }
    }

    private void assertContinuationProjection(McpServerProcess server, JsonNode produced) throws Exception {
        assertStoredContinuation(server, produced.path("progressSummary"));
        ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
        assertStoredContinuation(server, produced.path("progressSummary"));
    }

    private void assertStoredContinuation(McpServerProcess server, JsonNode progress) throws Exception {
        assertThat(progress.isObject()).isTrue();
        JsonNode items = query(server, "suite_continuation_state", Map.of("suiteRunId", "resume")).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("suiteRunId").asText()).isEqualTo("resume");
        assertThat(items.get(0).path("state")).isEqualTo(progress);
        assertThat(query(server, "suite_continuation_state", Map.of("suiteRunId", "absent")).path("items")).isEmpty();
    }

    @Test
    void nestedEvidenceProjectsOnceWithinTheExistingSizeLimit() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ObjectNode nested = JSON.createObjectNode().put("status", "blocked").put("reasonCode", "controlled_storage_input");
            ObjectNode details = nested.putObject("details").put("runStatus", "blocked").put("summary", "preserved metadata");
            var watchers = details.putArray("watchers");
            for (int index = 0; index < 14; index++) {
                watchers.addObject().put("id", "evidence-" + index).put("status", "blocked").put("evidence", "x".repeat(2800));
            }
            ObjectNode topLevel = nested.deepCopy();
            topLevel.set("watchers", topLevel.with("details").remove("watchers"));
            for (String run : List.of("nested", "top-level")) {
                ok(server, "run_result.upsert", Map.of("projectName", PROJECT, "planName", "plan", "runId", run,
                        "payload", run.equals("nested") ? nested : topLevel));
            }
            Path source = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/nested/execution.result.json");
            byte[] original = Files.readAllBytes(source);
            assertThat(original.length).isBetween(35_000, 64 * 1024);
            assertThat(ok(server, "run_result.read", runInput("nested")).path("artifact")).isEqualTo(nested);
            ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
            assertThat(query(server, "run_state", Map.of()).path("items")).hasSize(2);
            for (String run : List.of("nested", "top-level")) {
                assertThat(query(server, "watcher_state", Map.of("runId", run)).path("items").get(0).path("state")).isEqualTo(watchers);
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite"));
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT state_json FROM plan_runs ORDER BY run_id")) {
                for (int index = 0; index < 2; index++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(JSON.readTree(rows.getString(1))).isEqualTo(topLevel);
                }
                assertThat(rows.next()).isFalse();
            }
            assertThat(Files.readAllBytes(source)).isEqualTo(original);
        }
    }

    @Test
    void actualProducedWatcherVerificationResultsRebuildRollbackAndRestart() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path fixtureJar = repositoryRoot().resolve("test/fixtures/spring-apps/social-platform/user-service/user-app/target/user-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixtureJar).as("Build the selected Spring fixture prerequisite").isRegularFile();
        byte[] projectBefore;
        JsonNode produced;
        try (SpringFixtureTargetProcess fixture = SpringFixtureTargetProcess.start(fixtureJar);
                McpServerProcess server = start(workspace)) {
            fixture.awaitHealthy();
            createProject(server, workspace);
            projectBefore = Files.readAllBytes(workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json"));
            String url = "http://127.0.0.1:" + fixture.port() + "/actuator/health";
            ok(server, "regression_plan.upsert", planInput("regression", verifiedPlan(url)));
            JsonNode execution = execute(server, "regression_suite.execute_plan", Map.of("projectName", PROJECT,
                    "planName", "plan", "suiteRunId", "produced", "executionProfile", "regression"), true);
            assertThat(execution.path("result").path("details").path("runStatus").asText()).isEqualTo("pass");
            Path resultFile = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/produced-1/execution.result.json");
            produced = JSON.readTree(resultFile.toFile());
            assertThat(produced.path("status").asText()).isEqualTo("pass");
            assertThat(produced.path("details").path("watchers")).hasSize(1);
            assertThat(produced.path("details").path("watchers").get(0).path("status").asText()).isEqualTo("pass");
            assertThat(produced.path("details").path("externalVerification")).hasSize(1);
            assertThat(produced.path("details").path("externalVerification").get(0).path("status").asText()).isEqualTo("pass");
            assertThat(ok(server, "run_result.read", runInput("produced-1")).path("artifact").path("status").asText()).isEqualTo("pass");
            Map<String, Object> copy = new LinkedHashMap<>(runInput("stored-copy"));
            copy.put("payload", produced);
            ok(server, "run_result.upsert", copy);
            assertProjectionLifecycle(server, workspace, resultFile, produced);
            assertThat(Files.readAllBytes(workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json"))).isEqualTo(projectBefore);
        }
        try (McpServerProcess restarted = start(workspace)) {
            assertThat(ok(restarted, "run_result.read", runInput("produced-1")).path("artifact").path("status").asText()).isEqualTo("pass");
            assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(2);
            assertThat(Files.readAllBytes(workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json"))).isEqualTo(projectBefore);
        }
    }

    private static ObjectNode verifiedPlan(String url) {
        ObjectNode payload = plan("regression", url);
        ObjectNode contract = payload.with("contract");
        ObjectNode watcher = contract.putArray("watchers").addObject().put("id", "ready");
        watcher.putObject("dependency").put("stepOrder", 1);
        watcher.putObject("provider").put("type", "http").putObject("transport").putObject("request").put("method", "GET").put("url", url);
        watcher.putObject("waitPolicy").put("timeoutMs", 2000).put("retryMax", 2);
        watcher.set("expect", contract.path("steps").get(0).path("expect").deepCopy());
        ObjectNode verification = contract.putArray("externalVerification").addObject().put("id", "external-health");
        verification.putObject("provider").put("type", "http");
        verification.putObject("request").putObject("http").put("method", "GET").put("url", url);
        verification.set("expect", watcher.path("expect").deepCopy());
        return payload;
    }

    private void assertProjectionLifecycle(McpServerProcess server, Path workspace, Path source, JsonNode produced) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true))
                    .path("rebuild").path("replayedRuns").asInt()).isEqualTo(2);
        }
        assertThat(query(server, "watcher_state", Map.of()).path("items")).hasSize(2);
        assertThat(query(server, "external_verification_state", Map.of()).path("items")).hasSize(2);
        assertThat(query(server, "watcher_state", Map.of("runId", "produced-1")).path("items").get(0).path("state"))
                .isEqualTo(produced.path("details").path("watchers"));
        assertThat(query(server, "external_verification_state", Map.of("runId", "produced-1")).path("items").get(0).path("state"))
                .isEqualTo(produced.path("details").path("externalVerification"));
        JsonNode before = query(server, "run_state", Map.of()).path("items");
        Files.writeString(source, "{controlled interrupted JSON");
        blocked(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true), "state_store_rebuild_strict_failed");
        assertThat(query(server, "run_state", Map.of()).path("items")).isEqualTo(before);
        Files.writeString(source, produced.toString());
        ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
        assertPagesAndFilters(server);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(ok(server, "run_result.backfill", Map.of("projectName", PROJECT, "stateSurface", "correlation_state"))
                    .path("backfill").path("imported").asInt()).isZero();
            assertThat(ok(server, "run_result.cutover", Map.of("projectName", PROJECT)).path("cutover").path("status").asText()).isEqualTo("cutover");
        }
        assertThat(JSON.readTree(workspace.resolve(".mcpjvm/" + PROJECT + "/state-store.cutover.json").toFile())
                .path("status").asText()).isEqualTo("cutover_complete");
        assertSqliteRows(workspace, 2);
    }

    private void assertPagesAndFilters(McpServerProcess server) throws Exception {
        JsonNode page = query(server, "run_state", Map.of("pageSize", 1, "sortDirection", "asc"));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        JsonNode next = query(server, "run_state", Map.of("pageSize", 1, "sortDirection", "asc", "cursor", page.path("nextCursor").asText()));
        assertThat(next.path("items")).hasSize(1);
        assertThat(next.path("items").get(0).path("runId")).isNotEqualTo(page.path("items").get(0).path("runId"));
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(query(server, "run_state", Map.of("filters", Map.of("runId", "' OR 1=1 --"))).path("items")).isEmpty();
        assertThat(query(server, "run_state", Map.of("filters", Map.of("status", "pass", "executionProfile", "regression"))).path("items")).hasSize(2);
        blocked(server, "run_result.query", Map.of("projectName", PROJECT, "query", Map.of("cursor", "invalid")), "run_state_cursor_invalid");
        blocked(server, "run_result.query", Map.of("projectName", PROJECT, "query", Map.of("pageSize", 101)), "run_state_page_invalid");
        assertThat(query(server, "run_state", Map.of("sql", "DROP TABLE plan_runs")).path("items")).hasSize(2);
    }

    static void assertSqliteRows(Path workspace, int expected) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite"));
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM plan_runs")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"regression", "performance", "security"})
    void blockedRunStorageDefaultsRedactionAndBoundedRetention(String suite) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            for (int index = 1; index <= 3; index++) {
                Map<String, Object> input = new LinkedHashMap<>(runInput("blocked-" + index));
                input.put("suiteType", suite);
                input.put("payload", Map.of("status", "blocked", "reasonCode", "controlled_storage_input", "startedAt", index,
                        "endedAt", index, "authorization", SECRET, "continuation", Map.of("activePhase", "trigger", "complete", false)));
                assertThat(execute(server, "artifact_management.run_result.upsert", input, false).path("status").asText()).isEqualTo("confirmation_required");
                ok(server, "run_result.upsert", input);
            }
            Map<String, Object> selected = Map.of("projectName", PROJECT, "suiteType", suite, "planName", "plan");
            assertThat(ok(server, "run_result.list", selected).path("runIds")).hasSize(3);
            Map<String, Object> read = new LinkedHashMap<>(selected);
            read.put("runId", "blocked-1");
            assertThat(ok(server, "run_result.read", read).path("artifact").path("status").asText()).isEqualTo("blocked");
            Path source = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/" + suite + "/plan/runs/blocked-1/execution.result.json");
            assertThat(Files.readString(source)).doesNotContain(SECRET);
            ok(server, "run_result.rebuild", Map.of("projectName", PROJECT));
            assertThat(query(server, "run_state", Map.of("suiteType", suite, "detail", Map.of("continuation", true)))
                    .path("items").get(0).path("detail").path("continuation").path("complete").asBoolean(true)).isFalse();
            assertRetention(server);
            assertThat(source).isRegularFile();
        }
    }

    private void assertRetention(McpServerProcess server) throws Exception {
        assertThat(ok(server, "run_result.cleanup", Map.of("projectName", PROJECT)).path("cleanup").path("dryRun").asBoolean()).isTrue();
        Map<String, Object> retention = Map.of("dryRun", false, "terminalOlderThanDays", 1, "keepMostRecentTerminalRuns", 1, "maxDeleteBatch", 1);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(ok(server, "run_result.cleanup", Map.of("projectName", PROJECT, "retention", retention))
                    .path("cleanup").path("deletedRuns").asInt()).isEqualTo(1);
        }
        assertThat(query(server, "run_state", Map.of()).path("items")).hasSize(1);
        assertThat(query(server, "run_state", Map.of()).path("items").get(0).path("runId").asText()).isEqualTo("blocked-3");
        assertThat(ok(server, "run_result.cleanup", Map.of("projectName", PROJECT, "retention", retention))
                .path("cleanup").path("deletedRuns").asInt()).isZero();
    }

    @Test
    void lockedCorruptAndMissingStoresFailDeterministicallyWithoutLosingProjectData() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            assertThat(query(server, "run_state", Map.of()).path("status").asText()).isEqualTo("unavailable");
            assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite")).doesNotExist();
            createProject(server, workspace);
            Path project = workspace.resolve(".mcpjvm/" + PROJECT);
            byte[] authoritative = Files.readAllBytes(project.resolve("projects.json"));
            try (FileChannel channel = FileChannel.open(project.resolve("run-state.sqlite.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                for (String action : List.of("rebuild", "cutover", "cleanup")) {
                    blocked(server, "run_result." + action, Map.of("projectName", PROJECT), "state_store_busy");
                }
                blocked(server, "run_result.backfill", Map.of("projectName", PROJECT, "stateSurface", "correlation_state"), "state_store_busy");
            }
            Files.writeString(project.resolve("run-state.sqlite"), "controlled corrupt SQLite");
            blocked(server, "run_result.query", Map.of("projectName", PROJECT), "state_store_corrupt");
            assertThat(Files.readAllBytes(project.resolve("projects.json"))).isEqualTo(authoritative);
            assertThat(project.resolve("state-store.cutover.json")).doesNotExist();
            for (String action : List.of("read", "upsert", "list")) {
                Map<String, Object> input = new LinkedHashMap<>(Map.of("projectName", "../escape", "planName", "plan", "runId", "blocked"));
                if (action.equals("upsert")) {
                    input.put("payload", Map.of("status", "blocked"));
                }
                blocked(server, "run_result." + action, input, "artifact_path_segment_invalid");
            }
            assertThat(execute(server, "artifact_management.run_result.query", Map.of("projectName", PROJECT, "stateSurface", "sqlite_master"), true)
                    .path("status").asText()).isEqualTo("invalid_input");
        }
    }
}
