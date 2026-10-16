package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.ArtifactExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.impl.ExecuteExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.ExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.TrustedDirectSuiteRun;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class RunProjectionRecoveryTest {
    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SqliteRunStateStore sqlite = new SqliteRunStateStore(mapper);
    private final ArtifactJsonStore json = new ArtifactJsonStore(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"steps", "watchers", "externalVerification", "continuation"})
    void supportedLargeResultKeepsFullCanonicalDataAndBoundedExplicitProjection(String field) throws Exception {
        ObjectNode payload = mapper.createObjectNode().put("status", "pass");
        var rows = payload.putArray(field);
        for (int i = 0; i < 24; i++) {
            rows.addObject().put("id", "item-" + i).put("status", "pass").put("expected", "x".repeat(3000));
        }
        ObjectNode request = selector("large");
        request.set("payload", payload);
        assertThat(runs().upsert(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.UPSERT, request)).status()).isEqualTo("ok");
        Path canonical = run("large").resolve("execution.result.json");
        assertThat(Files.size(canonical)).isGreaterThan(64 * 1024);
        assertThat(mapper.readTree(canonical.toFile())).isEqualTo(payload);
        byte[] original = Files.readAllBytes(canonical);
        ObjectNode query = mapper.createObjectNode();
        query.putObject("detail").putArray("select").add(field);
        JsonNode queried = mapper.valueToTree(sqlite.query(database(), "demo", "run_state", query));
        assertThat(queried.path("items")).hasSize(1);
        assertThat(queried.path("items").get(0).path("status").asText()).isEqualTo("pass");
        assertThat(queried.path("items").get(0).path("projection").path("projectionStatus").asText()).isEqualTo("artifact_only");
        assertThat(queried.path("items").get(0).has("detail")).isFalse();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement();
                var stored = statement.executeQuery("SELECT length(CAST(state_json AS BLOB)) FROM plan_runs")) {
            assertThat(stored.next()).isTrue();
            assertThat(stored.getInt(1)).isLessThanOrEqualTo(64 * 1024);
        }
        sqlite.rebuild(database(), "demo", true);
        assertThat(Files.readAllBytes(canonical)).isEqualTo(original);
        assertThat(mapper.valueToTree(sqlite.query(database(), "demo")).path("items")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordered", "direct"})
    void retryRepairsProjectionWithoutReexecutingOrRewritingCanonicalEvidence(String mode) throws Exception {
        sqlite.ensure(database(), "demo");
        AtomicBoolean failProjection = new AtomicBoolean(true);
        ArtifactManagementFeature artifacts = artifacts(failProjection);
        AtomicInteger executions = new AtomicInteger();
        RegressionSuiteFeature regression = new RegressionSuiteFeature() {
            public RegressionSuiteResult preflight(JsonNode input) { return RegressionSuiteResult.ready(Map.of()); }
            public RegressionSuiteResult executePlan(JsonNode input) {
                executions.incrementAndGet();
                return RegressionSuiteResult.ready(Map.of("runStatus", "pass"));
            }
        };
        var checkpoints = new ArtifactExecutionSuiteStateStore(() -> Optional.of(workspace), json, sqlite, mapper);
        ExecutionRunLease lease = new ExecutionRunLease() {
            public boolean acquire(String project, String run) { return true; }
            public void release(String project, String run) { }
        };
        var ordered = new ExecuteExecutionOrchestrationAction(artifacts, value -> null, value -> null, regression, mapper,
                (p, s, n, r) -> Optional.of(run("retry-1").toString()), lease, checkpoints);
        var direct = new TrustedDirectSuiteRun(artifacts, (p, s, n, r) -> Optional.of(run("retry-1").toString()), mapper);
        ObjectNode input = selector("retry-1").put("suiteRunId", "retry").put("executionProfile", "review");
        java.util.function.Supplier<String> execute = () -> {
            if (mode.equals("ordered")) {
                return ordered.execute(new ExecutionOrchestrationRequest(ExecutionOrchestrationAction.EXECUTE, input)).status();
            }
            return direct.execute("regression", input, RegressionSuiteResult.class, regression::executePlan,
                    reason -> RegressionSuiteResult.blocked(reason, "retry", Map.of()), true).status();
        };
        assertThat(execute.get()).isNotEqualTo("pass").isNotEqualTo("ready");
        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (String name : List.of("execution.result.json", "evidence.json", "context.resolved.json")) {
            Path path = run("retry-1").resolve(name);
            before.put(path, Files.readAllBytes(path));
        }
        assertThat(execute.get()).isNotEqualTo("pass").isNotEqualTo("ready");
        assertThat(mapper.valueToTree(sqlite.query(database(), "demo")).path("items")).isEmpty();
        failProjection.set(false);
        assertThat(execute.get()).isIn("pass", "ready");
        assertThat(executions).hasValue(1);
        assertThat(mapper.valueToTree(sqlite.query(database(), "demo")).path("items")).hasSize(1);
        for (var entry : before.entrySet()) {
            assertThat(Files.readAllBytes(entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    private RunResultOperations runs() {
        return new RunResultOperations(new ArtifactManagementSupport(() -> Optional.of(workspace), json, sqlite, mapper));
    }

    private ArtifactManagementFeature artifacts(AtomicBoolean fail) {
        return artifacts(fail, "stop_on_fail", "inherit", List.of("plan"));
    }

    @ParameterizedTest
    @CsvSource({"pass,continue_on_fail,inherit", "pass,stop_on_fail,continue",
            "in_progress,continue_on_fail,inherit", "in_progress,stop_on_fail,inherit"})
    void orderedRecoveryIsRequiredBeforeAnyOwnerOrContinuation(String phase, String policy, String onFail) throws Exception {
        sqlite.ensure(database(), "demo");
        AtomicBoolean locked = new AtomicBoolean(true);
        var artifacts = artifacts(locked, policy, onFail, List.of("plan", "later"));
        List<String> executions = new ArrayList<>();
        RegressionSuiteFeature regression = new RegressionSuiteFeature() {
            public RegressionSuiteResult preflight(JsonNode input) { return RegressionSuiteResult.ready(Map.of()); }
            public RegressionSuiteResult executePlan(JsonNode input) {
                if (!locked.get()) {
                    var rows = mapper.valueToTree(sqlite.query(database(), "demo")).path("items");
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).path("status").asText()).isEqualTo(phase);
                    if (phase.equals("in_progress")) {
                        assertThat(input.path("continuation").path("phase").asText()).isEqualTo("watchers");
                    }
                }
                executions.add(input.path("planName").asText());
                if (locked.get() && phase.equals("in_progress")) {
                    return new RegressionSuiteResult("in_progress", "ok", null, Map.of(),
                            Map.of("continuation", Map.of("phase", "watchers", "cursor", 1)));
                }
                return RegressionSuiteResult.ready(Map.of("runStatus", "pass"));
            }
        };
        var checkpoints = new ArtifactExecutionSuiteStateStore(() -> Optional.of(workspace), json, sqlite, mapper);
        ExecutionRunLease lease = new ExecutionRunLease() {
            public boolean acquire(String project, String run) { return true; }
            public void release(String project, String run) { }
        };
        var ordered = new ExecuteExecutionOrchestrationAction(artifacts, value -> null, value -> null, regression, mapper,
                (p, s, n, r) -> Optional.of(run("retry-1").toString()), lease, checkpoints);
        var request = new ExecutionOrchestrationRequest(ExecutionOrchestrationAction.EXECUTE,
                mapper.createObjectNode().put("projectName", "demo").put("executionProfile", "review").put("suiteRunId", "retry"));
        assertThat(ordered.execute(request).status()).isEqualTo("blocked");
        assertThat(executions).containsExactly("plan");
        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (String name : List.of("execution.result.json", "evidence.json", "context.resolved.json")) {
            Path path = run("retry-1").resolve(name);
            before.put(path, Files.readAllBytes(path));
        }
        var retry = ordered.execute(request);
        assertThat(retry.reasonCode()).isEqualTo("execution_run_artifact_persist_failed");
        assertThat(retry.reasonMeta()).containsEntry("projectionReasonCode", "state_store_busy");
        assertThat(executions).containsExactly("plan");
        for (var entry : before.entrySet()) { assertThat(Files.readAllBytes(entry.getKey())).isEqualTo(entry.getValue()); }
        locked.set(false);
        ObjectNode resumed = (ObjectNode) request.input().deepCopy();
        resumed.put("maxPlansPerCall", 1);
        assertThat(ordered.execute(new ExecutionOrchestrationRequest(ExecutionOrchestrationAction.EXECUTE, resumed)).status())
                .isIn("pass", "in_progress");
        assertThat(executions).containsExactly("plan", phase.equals("in_progress") ? "plan" : "later");
    }

    private ArtifactManagementFeature artifacts(AtomicBoolean fail, String policy, String onFail, List<String> plans) {
        return request -> {
            if (request.artifactType() == ArtifactType.RUN_RESULT) {
                if (request.action() == ArtifactAction.READ) { return runs().read(request); }
                if (!fail.get()) { return runs().upsert(request); }
                try (var channel = FileChannel.open(database().resolveSibling("run-state.sqlite.lock"), StandardOpenOption.WRITE);
                        var lock = channel.lock()) {
                    return runs().upsert(request);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            Map<String, Object> profile = Map.of("workspaces", List.of(Map.of("projectRoot", workspace.toString(),
                    "executionProfiles", List.of(Map.of("executionProfile", "review", "suiteType", "regression",
                            "executionPolicy", policy, "plans", plans.stream().map(name -> Map.of("order", plans.indexOf(name) + 1,
                                    "planName", name, "onFail", onFail)).toList())))));
            return ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of("artifact",
                    request.artifactType() == ArtifactType.PROJECT_CONTEXT ? profile : Map.of("metadata", Map.of(), "contract", Map.of())));
        };
    }

    private ObjectNode selector(String id) {
        return mapper.createObjectNode().put("projectName", "demo").put("suiteType", "regression").put("planName", "plan").put("runId", id);
    }

    private Path run(String id) { return workspace.resolve(".mcpjvm/demo/plans/regression/plan/runs/" + id); }
    private Path database() { return workspace.resolve(".mcpjvm/demo/run-state.sqlite"); }
}
