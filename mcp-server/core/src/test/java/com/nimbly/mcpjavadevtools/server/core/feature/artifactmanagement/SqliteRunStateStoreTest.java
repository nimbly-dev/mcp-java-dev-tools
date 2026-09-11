package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies transactional SQLite projection lifecycle guarantees. */
class SqliteRunStateStoreTest {

    @TempDir
    Path workspace;

    @Test
    void rebuildAndBackfillAreIdempotentAcrossRepeatedCalls() throws Exception {
        Path project = workspace.resolve(".mcpjvm/demo");
        Path run = project.resolve("plans/performance/load/runs/run-1");
        Files.createDirectories(run);
        Files.writeString(run.resolve("execution.result.json"),
                "{\"status\":\"pass\",\"correlations\":[{\"correlationSessionId\":\"s1\"}],"
                        + "\"watchers\":[{\"status\":\"pass\"}],\"externalVerification\":{\"status\":\"pass\"}}");
        Files.writeString(run.resolve("correlation.json"), "{\"correlationSessionId\":\"legacy\"}");
        Path database = project.resolve("run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());

        Map<String, Object> first = store.rebuild(database, "demo");
        Map<String, Object> second = store.rebuild(database, "demo");
        Map<String, Object> backfillFirst = store.backfill(database, "demo");
        Map<String, Object> backfillSecond = store.backfill(database, "demo");
        Map<String, Object> cutover = store.cutover(database, "demo");

        assertThat(first).containsEntry("replayedRuns", 1);
        assertThat(second).containsEntry("replayedRuns", 1);
        assertThat(backfillFirst).containsEntry("imported", 1);
        assertThat(backfillSecond).containsEntry("imported", 0);
        assertThat(cutover).containsEntry("status", "cutover");
        assertThat(Files.readString(project.resolve("state-store.cutover.json")))
                .contains("cutover_complete");
        List<?> correlationItems = (List<?>) store.query(database, "demo", "correlation_state").get("items");
        assertThat(correlationItems).isNotEmpty();
        assertThat(correlationItems.getFirst().toString()).contains("legacy");
        assertThat(store.query(database, "demo", "watcher_state").get("items"))
                .isInstanceOf(java.util.List.class);
    }

    @Test
    void strictRebuildRollsBackAndPreservesPreviousProjection() throws Exception {
        Path run = workspace.resolve(".mcpjvm/demo/plans/regression/plan/runs/run-1");
        Files.createDirectories(run);
        Path result = run.resolve("execution.result.json");
        Files.writeString(result, "{\"status\":\"pass\",\"startedAt\":100,\"endedAt\":200}");
        Path database = workspace.resolve(".mcpjvm/demo/run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());
        store.rebuild(database, "demo");
        Map<String, Object> before = store.query(database, "demo", "run_state");

        Files.writeString(result, "{not-json");

        assertThatThrownBy(() -> store.rebuild(database, "demo", true))
                .isInstanceOf(ArtifactOperationException.class)
                .hasMessageContaining("strict rebuild rejected");
        Map<String, Object> after = store.query(database, "demo", "run_state");

        assertThat(after.get("items")).isEqualTo(before.get("items"));
        assertThat(after.get("items").toString()).contains("run-1", "pass");

        Files.writeString(result, "{\"status\":\"fail\",\"startedAt\":300,\"endedAt\":400}");
        Map<String, Object> resumed = store.rebuild(database, "demo", true);

        assertThat(resumed).containsEntry("replayedRuns", 1);
        assertThat(store.query(database, "demo", "run_state").get("items").toString())
                .contains("run-1", "fail")
                .doesNotContain("pass");
    }

    @Test
    void cleanupDeletesOnlyBoundedExpiredRowsAndPreservesNewestProtectedRow() throws Exception {
        Path runs = workspace.resolve(".mcpjvm/demo/plans/regression/plan/runs");
        for (int index = 1; index <= 3; index++) {
            Path run = runs.resolve("run-" + index);
            Files.createDirectories(run);
            Files.writeString(run.resolve("execution.result.json"),
                    "{\"status\":\"pass\",\"startedAt\":" + index
                            + ",\"endedAt\":" + index + "}");
        }
        Path database = workspace.resolve(".mcpjvm/demo/run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());
        store.rebuild(database, "demo");

        Map<String, Object> first = store.cleanup(database, "demo", false, 1, 1, 1);
        Map<String, Object> second = store.cleanup(database, "demo", false, 1, 1, 1);
        String remaining = store.query(database, "demo", "run_state").get("items").toString();

        assertThat(first).containsEntry("deletedRuns", 1);
        assertThat(second).containsEntry("deletedRuns", 1);
        assertThat(remaining).contains("run-3").doesNotContain("run-1", "run-2");
    }

    @Test
    void concurrentStoreOperationFailsClosedOnExplicitLock() throws Exception {
        Path database = workspace.resolve(".mcpjvm/demo/run-state.sqlite");
        Path lock = database.resolveSibling("run-state.sqlite.lock");
        Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var fileLock = channel.lock()) {
            SqliteRunStateStore store = new SqliteRunStateStore();
            assertThatThrownBy(() -> store.ensure(database, "demo"))
                    .isInstanceOf(ArtifactOperationException.class)
                    .hasMessageContaining("locked");
        }
    }

    @Test
    void queryAppliesFiltersSortingPaginationAndExecutionProfile() throws Exception {
        Path project = workspace.resolve(".mcpjvm/demo");
        Path first = project.resolve("plans/regression/plan/runs/first");
        Path second = project.resolve("plans/regression/plan/runs/second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("execution.result.json"),
                "{\"status\":\"pass\",\"executionProfile\":\"fast\","
                        + "\"startedAt\":100,\"endedAt\":200}");
        Files.writeString(second.resolve("execution.result.json"),
                "{\"status\":\"pass\",\"executionProfile\":\"slow\","
                        + "\"startedAt\":300,\"endedAt\":400}");
        Path database = project.resolve("run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());
        store.rebuild(database, "demo");

        ObjectNode query = new ObjectMapper().createObjectNode();
        query.put("executionProfile", "fast");
        query.put("sortDirection", "asc");
        query.put("pageSize", 1);
        Map<String, Object> firstPage = store.query(database, "demo", "run_state", query);

        assertThat(firstPage.get("pageSize")).isEqualTo(1);
        assertThat((Boolean) firstPage.get("hasMore")).isFalse();
        assertThat(firstPage.get("items").toString()).contains("first", "fast").doesNotContain("second");
    }

    @Test
    void queryFiltersCompleteSourceBeforeApplyingTheOutputPageLimit() throws Exception {
        Path plans = workspace.resolve(".mcpjvm/demo/plans/regression/plan/runs");
        for (int index = 0; index <= 1000; index++) {
            Path run = plans.resolve("run-" + index);
            Files.createDirectories(run);
            String profile = index == 1000 ? "fast" : "slow";
            Files.writeString(run.resolve("execution.result.json"),
                    "{\"status\":\"pass\",\"executionProfile\":\"" + profile
                            + "\",\"startedAt\":" + index + "}");
        }
        Path database = workspace.resolve(".mcpjvm/demo/run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());
        store.rebuild(database, "demo");

        ObjectNode query = new ObjectMapper().createObjectNode();
        query.put("executionProfile", "fast");
        query.put("pageSize", 1);
        Map<String, Object> result = store.query(database, "demo", "run_state", query);

        assertThat(result.get("items").toString()).contains("run-1000");
        assertThat(result.get("items").toString()).doesNotContain("run-999");
    }
}
