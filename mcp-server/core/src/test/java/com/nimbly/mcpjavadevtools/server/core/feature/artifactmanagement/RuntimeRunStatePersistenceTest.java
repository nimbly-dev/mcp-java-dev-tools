package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.ArtifactExecutionSuiteStateStore;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeRunStatePersistenceTest {
    @TempDir
    Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqliteRunStateStore store = new SqliteRunStateStore(mapper);

    @Test
    void incrementalWritesUpdateOnlySelectedRunWithoutScanningHistory() throws Exception {
        Path database = database();
        for (String suite : List.of("regression", "performance", "security")) {
            Path source = workspace.resolve(".mcpjvm/demo/plans/" + suite + "/plan/runs/run/execution.result.json");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "{\"status\":\"pass\",\"authorization\":\"secret-value\",\"suiteRunId\":\"suite\"}");
            store.projectRun(database, "demo", suite, "plan", "run");
        }
        Path malformed = workspace.resolve(".mcpjvm/demo/plans/regression/old/runs/bad/execution.result.json");
        Files.createDirectories(malformed.getParent());
        Files.writeString(malformed, "invalid historical JSON");
        Path regression = workspace.resolve(".mcpjvm/demo/plans/regression/plan/runs/run/execution.result.json");
        Files.writeString(regression, "{\"status\":\"blocked\"}");
        store.projectRun(database, "demo", "regression", "plan", "run");
        List<?> rows = (List<?>) store.query(database, "demo").get("items");
        assertThat(rows).hasSize(3);
        assertThat(rows.toString()).contains("blocked").doesNotContain("secret-value");
        Files.writeString(regression, "invalid");
        assertThatThrownBy(() -> store.projectRun(database, "demo", "regression", "plan", "run"))
                .isInstanceOf(ArtifactOperationException.class).hasMessageContaining("source is invalid");
        assertThat(store.query(database, "demo").get("items")).isEqualTo(rows);
    }

    @Test
    void suiteProgressPersistsImmediatelyAndSurvivesRestartAndExplicitRebuild() {
        var checkpoints = checkpoints();
        Map<String, Object> progress = Map.of("completedPlanCount", 1, "totalPlanCount", 2);
        assertThat(checkpoints.write("demo", "suite", state("in_progress", progress))).isPresent();
        assertContinuation(progress);
        assertThat(checkpoints().read("demo", "suite")).contains(state("in_progress", progress));
        Map<String, Object> terminal = Map.of("completedPlanCount", 2, "totalPlanCount", 2);
        assertThat(checkpoints.write("demo", "suite", state("pass", terminal))).isPresent();
        store.rebuild(database(), "demo", true);
        assertContinuation(terminal);
        assertThat(store.query(database(), "demo").get("items")).as("suite checkpoints are not fake plan runs").isEqualTo(List.of());
    }

    @Test
    void lockedProjectionDoesNotClaimCheckpointSuccessOrReplaceExistingSqliteState() throws Exception {
        var checkpoints = checkpoints();
        Map<String, Object> progress = Map.of("completedPlanCount", 1);
        assertThat(checkpoints.write("demo", "suite", state("in_progress", progress))).isPresent();
        try (var channel = FileChannel.open(database().resolveSibling("run-state.sqlite.lock"), StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThat(checkpoints.write("demo", "suite", state("pass", Map.of("completedPlanCount", 2)))).isEmpty();
        }
        assertContinuation(progress);
        assertThat(checkpoints.read("demo", "suite").orElseThrow()).containsEntry("status", "pass");
        assertThat(checkpoints.write("../outside", "suite", state("pass", progress))).isEmpty();
        assertThat(Files.exists(workspace.resolve("outside"))).isFalse();
    }

    private Path database() {
        return workspace.resolve(".mcpjvm/demo/run-state.sqlite");
    }

    @Test
    void emptySuiteCheckpointCannotBecomeAValidProjectionOrErasePriorState() throws Exception {
        Map<String, Object> progress = Map.of("completedPlanCount", 1);
        assertThat(checkpoints().write("demo", "suite", state("in_progress", progress))).isPresent();
        Files.writeString(workspace.resolve(".mcpjvm/demo/suite-runs/suite/execution_orchestration.result.json"), "");
        assertThat(checkpoints().read("demo", "suite")).isEmpty();
        assertThatThrownBy(() -> store.projectSuite(database(), "demo", "suite"))
                .isInstanceOf(ArtifactOperationException.class);
        assertThatThrownBy(() -> store.rebuild(database(), "demo", true))
                .isInstanceOf(ArtifactOperationException.class);
        assertContinuation(progress);
    }

    private ArtifactExecutionSuiteStateStore checkpoints() {
        return new ArtifactExecutionSuiteStateStore(() -> Optional.of(workspace), new ArtifactJsonStore(mapper), store, mapper);
    }

    private Map<String, Object> state(String status, Map<String, Object> progress) {
        return Map.of("suiteRunId", "suite", "status", status, "progressSummary", progress);
    }

    private void assertContinuation(Map<String, Object> expected) {
        var query = new SqliteRunStateStore(mapper).query(database(), "demo", "suite_continuation_state");
        var rows = mapper.valueToTree(query).path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("state")).isEqualTo(mapper.valueToTree(expected));
        assertThat(rows.get(0).path("suiteRunId").asText()).isEqualTo("suite");
    }
}
