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
