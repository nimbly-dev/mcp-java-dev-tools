package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.impl.ExecuteExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.ExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.ExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionRunDirectoryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.operation.ExecutionOrchestrationOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ExecuteExecutionOrchestrationActionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> suiteStates = new HashMap<>();

    @Test
    void blocksMissingProjectSelectionWithStableReasonCode() throws Exception {
        var result = feature(artifacts()).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE, mapper.readTree("{\"executionProfile\":\"nightly\"}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("project_name_required");
    }

    @Test
    void routesAnOrderedPerformancePlanThroughItsPublicFeature() throws Exception {
        var result = feature(artifacts()).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE, mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\"}")));

        assertThat(result.status()).isEqualTo("pass");
        assertThat(result.reasonCode()).isEqualTo("ok");
        assertThat(result.details().get("planRuns").toString()).contains("perf-smoke").contains("completed");
    }

    @Test
    void resumesAtTheFirstPlanWithoutAPersistedTerminalRun() throws Exception {
        Map<String, JsonNode> runs = new HashMap<>();
        ArtifactManagementFeature artifacts = statefulArtifacts(runs);
        var first = feature(artifacts).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"resume\",\"maxPlansPerCall\":1}")));
        var resumed = feature(artifacts).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"resume\",\"maxPlansPerCall\":1}")));

        assertThat(first.status()).isEqualTo("in_progress");
        assertThat(resumed.status()).isEqualTo("pass");
        assertThat(resumed.details().get("planRuns").toString()).contains("perf-load");
    }

    @Test
    void blocksConcurrentOwnershipOfTheSameSuiteRun() throws Exception {
        ExecutionRunLease deniedLease = new ExecutionRunLease() {
            @Override
            public boolean acquire(String projectName, String suiteRunId) {
                return false;
            }

            @Override
            public void release(String projectName, String suiteRunId) {
            }
        };
        var result = feature(artifacts(), deniedLease).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"active\"}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("execution_suite_run_active");
    }

    @Test
    void preservesAPersistedFailedPlanAsTheAggregateResumeOutcome() throws Exception {
        Map<String, JsonNode> runs = new HashMap<>();
        ArtifactManagementFeature artifacts = statefulArtifacts(runs);
        runs.put("perf-smoke:failed-1", mapper.readTree("""
                {"status":"fail","reasonCode":"threshold_exceeded","details":{"runStatus":"fail"}}
                """));

        var result = feature(artifacts).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"failed\"}")));

        assertThat(result.status()).isEqualTo("partial_fail");
        assertThat(result.reasonCode()).isEqualTo("execution_suite_partial_fail");
        assertThat(result.details().get("planRuns").toString()).contains("perf-smoke").contains("fail");
    }

    @Test
    void rejectsResumeWhenTheDurableSuiteCheckpointBelongsToAnotherProfile() throws Exception {
        suiteStates.put("resume", mapper.convertValue(mapper.readTree("""
                {"projectName":"demo","executionProfile":"other","suiteType":"performance"}
                """), Map.class));

        var result = feature(statefulArtifacts(new HashMap<>())).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"resume\"}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("execution_suite_checkpoint_context_mismatch");
    }

    @Test
    void rejectsResumeWhenThePersistedProfileDefinitionFingerprintChanges() throws Exception {
        suiteStates.put("resume", mapper.convertValue(mapper.readTree("""
                {"projectName":"demo","executionProfile":"nightly","suiteType":"performance",
                "profileFingerprint":"changed-plan-definition"}
                """), Map.class));

        var result = feature(statefulArtifacts(new HashMap<>())).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"resume\"}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("execution_suite_checkpoint_definition_mismatch");
    }

    @Test
    void persistsLifecycleEvidenceAndFailsClosedOnCleanupFailure() throws Exception {
        ExecutionRuntimeLifecycle lifecycle = new ExecutionRuntimeLifecycle() {
            @Override
            public RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request) {
                return new RuntimeLifecycleResult(true, true, "attached", Map.of("pid", "123", "status", "attached"));
            }

            @Override
            public RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request) {
                return RuntimeLifecycleResult.blocked("cleanup_unverified", Map.of("status", "cleanup_unverified"));
            }
        };
        var result = feature(artifacts(), lifecycle).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE, mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\"}")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("cleanup_unverified");
        assertThat(suiteStates.values().iterator().next()).containsKey("runtimeLifecycle");
    }

    @Test
    void retainsAttachedLifecycleForSlicedResumeAndCleansUpOnceAtTerminalCompletion() throws Exception {
        Map<String, JsonNode> runs = new HashMap<>();
        AtomicInteger cleanupCalls = new AtomicInteger();
        ExecutionRuntimeLifecycle lifecycle = lifecycle(cleanupCalls);
        var first = feature(statefulArtifacts(runs), lifecycle).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"slice\",\"maxPlansPerCall\":1}")));
        var resumed = feature(statefulArtifacts(runs), lifecycle).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"slice\",\"maxPlansPerCall\":1}")));

        assertThat(first.status()).isEqualTo("in_progress");
        assertThat(cleanupCalls).hasValue(1);
        assertThat(resumed.status()).isEqualTo("pass");
        assertThat(suiteStates.get("slice").get("runtimeLifecycle").toString()).contains("terminal");
    }

    @Test
    void promotesOnlyNamedNonSecretScalarContext() throws Exception {
        var result = feature(artifacts()).execute(new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE, mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\"}")));

        assertThat(result.details().get("suiteContext").toString()).contains("trace-from-plan");
        assertThat(result.toString()).doesNotContain("authorization").doesNotContain("secret");
    }

    @Test
    void registersTheOrchestrationRowWithBoundedResumeAndCancellationMetadata() throws Exception {
        var feature = (DefaultExecutionOrchestrationFeature) feature(statefulArtifacts(new HashMap<>()));
        var registration = ExecutionOrchestrationOperationRegistrations.create(feature, mapper).getFirst();

        assertThat(registration.descriptor().operationId().value()).isEqualTo("execution_orchestration.execute");
        assertThat(registration.descriptor().executableOwner())
                .contains("ExecuteExecutionOrchestrationAction#execute");
        assertThat(registration.provenance().actionless()).isFalse();
        assertThat(registration.provenance().invocationAction()).isEqualTo("execute");
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.CONTEXT_AWARE_CANCELLATION);

        JsonNode result = registration.execute(mapper.readTree("""
                {"projectName":"demo","executionProfile":"nightly",
                "suiteRunId":"registered-resume","maxPlansPerCall":1}
                """));
        assertThat(result.path("status").asText()).isEqualTo("in_progress");
        assertThat(result.path("details").path("nextPlanOrder").asInt()).isEqualTo(2);

        writeContractEvidence(registration);
    }

    @Test
    void cancellationDuringActiveExecutionCleansRuntimeAndReleasesLease() throws Exception {
        ExecutionRunLease lease = new com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.InMemoryExecutionRunLease();
        AtomicInteger cleanupCalls = new AtomicInteger();
        ExecutionRuntimeLifecycle lifecycle = lifecycle(cleanupCalls);
        PerformanceSuiteFeature performance = request -> {
            Thread.currentThread().interrupt();
            return PerformanceSuiteResult.completed(runDetails());
        };
        ExecutionOrchestrationFeature feature = feature(artifacts(), performance, lifecycle, lease);
        ExecutionOrchestrationRequest request = new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"cancelled\"}"));

        try {
            assertThatThrownBy(() -> feature.execute(request)).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(cleanupCalls).hasValue(1);
        assertThat(suiteStates.get("cancelled").get("runtimeLifecycle").toString()).contains("terminal");
        assertThat(lease.acquire("demo", "cancelled")).isTrue();
        lease.release("demo", "cancelled");
    }

    @Test
    void cancellationAfterTerminalPlanPersistenceKeepsPromotedContextForResume() throws Exception {
        Map<String, JsonNode> runs = new HashMap<>();
        ArtifactManagementFeature delegate = statefulArtifacts(runs);
        AtomicBoolean interruptAfterFirstPersist = new AtomicBoolean(true);
        ArtifactManagementFeature artifacts = request -> {
            ArtifactManagementResult result = delegate.execute(request);
            if (request.artifactType() == ArtifactType.RUN_RESULT
                    && request.action() == ArtifactAction.UPSERT
                    && interruptAfterFirstPersist.getAndSet(false)) {
                Thread.currentThread().interrupt();
            }
            return result;
        };
        ExecutionRunLease lease = new com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.InMemoryExecutionRunLease();
        ExecutionOrchestrationFeature feature = feature(artifacts, lease);
        ExecutionOrchestrationRequest request = new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"resume-context\"}"));

        try {
            assertThatThrownBy(() -> feature.execute(request)).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }

        assertThat(runs).containsKey("perf-smoke:resume-context-1");
        assertThat(suiteStates.get("resume-context").get("suiteContext").toString()).contains("trace-from-plan");
        var resumed = feature.execute(request);
        assertThat(resumed.status()).isEqualTo("pass");
        assertThat(resumed.details().get("suiteContext").toString()).contains("trace-from-plan");
        assertThat(resumed.details().get("planRuns").toString()).contains("perf-smoke", "perf-load");
    }

    @Test
    void deadlineDuringActiveExecutionCleansRuntimeAndReleasesLease() throws Exception {
        ExecutionRunLease lease = new com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.InMemoryExecutionRunLease();
        AtomicInteger cleanupCalls = new AtomicInteger();
        PerformanceSuiteFeature performance = request -> {
            while (!OperationExecutionContext.current().cancellationRequested()) {
                Thread.onSpinWait();
            }
            return PerformanceSuiteResult.completed(runDetails());
        };
        ExecutionOrchestrationFeature feature = feature(
                artifacts(), performance, lifecycle(cleanupCalls), lease);
        ExecutionOrchestrationRequest request = new ExecutionOrchestrationRequest(
                ExecutionOrchestrationAction.EXECUTE,
                mapper.readTree("{\"projectName\":\"demo\",\"executionProfile\":\"nightly\",\"suiteRunId\":\"timed-out\"}"));

        try (var ignored = OperationExecutionContext.forTimeout(100).install()) {
            assertThatThrownBy(() -> feature.execute(request)).isInstanceOf(CancellationException.class);
        }

        assertThat(cleanupCalls).hasValue(1);
        assertThat(suiteStates.get("timed-out").get("runtimeLifecycle").toString()).contains("terminal");
        assertThat(lease.acquire("demo", "timed-out")).isTrue();
        lease.release("demo", "timed-out");
    }

    private void writeContractEvidence(
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration<?, ?> registration)
            throws Exception {
        OperationId id = registration.descriptor().operationId();
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        OperationDirectory directory = new OperationDirectory(
                List.of(registration),
                new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id))), mapper);
        Path evidence = Path.of("target", "mcpjvm-622-evidence");
        Files.createDirectories(evidence);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest-execution-orchestration.json").toFile(),
                directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-execution-orchestration.json").toFile(), directory.traceInventory());

        Path root = Path.of("src", "main", "java", "com", "nimbly", "mcpjavadevtools", "server", "core", "feature");
        List<Map<String, Object>> measured = List.of(
                measuredRoutingFile(root.resolve("transportexecution/DefaultTransportExecutionFeature.java"), 27),
                measuredRoutingFile(root.resolve("executionprofileexport/DefaultExecutionProfileExportFeature.java"), 20),
                measuredRoutingFile(root.resolve("executionorchestration/DefaultExecutionOrchestrationFeature.java"), 25));
        int assessedDelta = measured.stream().mapToInt(value -> (Integer) value.get("delta")).sum();
        assertThat(assessedDelta).isLessThanOrEqualTo(0);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("routing-inventory.json").toFile(),
                Map.ofEntries(
                        Map.entry("baselineRevision", "90b9ee6ee17876e532d182530424ce8e34cc5743"),
                        Map.entry("operationIds", List.of(
                                "execution_orchestration.execute", "execution_profile_export.export",
                                "transport_execute.execute")),
                        Map.entry("measure", "nonblank source lines"),
                        Map.entry("routingShells", measured),
                        Map.entry("routingPlumbingNonblankDelta", assessedDelta),
                        Map.entry("added", List.of()),
                        Map.entry("removed", List.of()),
                        Map.entry("retained", List.of(
                                "ExecuteTransportAction", "TransportExecutionActionHandler",
                                "ExportExecutionProfileOperation", "ExecutionProfileExportOperationCatalog",
                                "ExecuteExecutionOrchestrationAction", "ExecutionOrchestrationActionHandler",
                                "TransportExecutionOperationRegistrations",
                                "ExecutionProfileExportOperationRegistrations",
                                "ExecutionOrchestrationOperationRegistrations")),
                        Map.entry("compatibilityRetained", List.of(
                                Map.of("path", root.resolve("transportexecution/DefaultTransportExecutionFeature.java").toString(),
                                        "caller", "TransportExecuteMcpTool", "deletionCondition", "#611 adapter removal"),
                                Map.of("path", root.resolve("executionorchestration/DefaultExecutionOrchestrationFeature.java").toString(),
                                        "caller", "ExecutionOrchestrationMcpTool", "deletionCondition", "#611 adapter removal"))),
                        Map.entry("excludedRequiredSubstantiveChanges", List.of(
                                "operation registrations: schemas, binding, normalization, cancellation metadata",
                                "orchestration action: cancellation cleanup and durable continuation"))));
    }

    private static Map<String, Object> measuredRoutingFile(Path path, int before) throws Exception {
        int after = (int) Files.readAllLines(path).stream().filter(line -> !line.isBlank()).count();
        return Map.of("path", path.toString(), "before", before, "after", after, "delta", after - before);
    }

    private ExecutionOrchestrationFeature feature(ArtifactManagementFeature artifacts) {
        return feature(artifacts, new com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.InMemoryExecutionRunLease());
    }

    private ExecutionOrchestrationFeature feature(ArtifactManagementFeature artifacts, ExecutionRunLease lease) {
        SecuritySuiteFeature security = request -> SecuritySuiteResult.completed(Map.of("runStatus", "pass"));
        return feature(artifacts, security, lease);
    }

    private ExecutionOrchestrationFeature feature(ArtifactManagementFeature artifacts, ExecutionRuntimeLifecycle lifecycle) {
        PerformanceSuiteFeature performance = request -> PerformanceSuiteResult.completed(runDetails());
        return feature(artifacts, performance, lifecycle,
                new com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.InMemoryExecutionRunLease());
    }

    private ExecutionOrchestrationFeature feature(
            ArtifactManagementFeature artifacts,
            PerformanceSuiteFeature performance,
            ExecutionRuntimeLifecycle lifecycle,
            ExecutionRunLease lease) {
        SecuritySuiteFeature security = request -> SecuritySuiteResult.completed(Map.of("runStatus", "pass"));
        RegressionSuiteFeature regression = request -> RegressionSuiteResult.ready(Map.of("runStatus", "pass"));
        ExecutionRunDirectoryProvider directories = (project, suite, plan, run) -> java.util.Optional.of("target/runs/" + run);
        return new DefaultExecutionOrchestrationFeature(List.of(new ExecuteExecutionOrchestrationAction(
                artifacts, performance, security, regression, mapper, directories, lease, suiteState(), lifecycle)));
    }

    private ExecutionOrchestrationFeature feature(
            ArtifactManagementFeature artifacts, SecuritySuiteFeature security, ExecutionRunLease lease) {
        PerformanceSuiteFeature performance = request -> PerformanceSuiteResult.completed(runDetails());
        RegressionSuiteFeature regression = request -> RegressionSuiteResult.ready(Map.of("runStatus", "pass"));
        ExecutionRunDirectoryProvider directories = (project, suite, plan, run) -> java.util.Optional.of("target/runs/" + run);
        return new DefaultExecutionOrchestrationFeature(List.of(
                new ExecuteExecutionOrchestrationAction(
                        artifacts, performance, security, regression, mapper, directories, lease, suiteState())));
    }

    private ExecutionSuiteStateStore suiteState() {
        return new ExecutionSuiteStateStore() {
            @Override
            public java.util.Optional<Map<String, Object>> read(String projectName, String suiteRunId) {
                return java.util.Optional.ofNullable(suiteStates.get(suiteRunId));
            }

            @Override
            public java.util.Optional<String> write(String projectName, String suiteRunId, Map<String, Object> state) {
                suiteStates.put(suiteRunId, Map.copyOf(state));
                return java.util.Optional.of(".mcpjvm/" + projectName + "/suite-runs/" + suiteRunId
                        + "/execution_orchestration.result.json");
            }
        };
    }

    private ArtifactManagementFeature artifacts() {
        return request -> {
            if (request.artifactType() == ArtifactType.RUN_RESULT && request.action() == ArtifactAction.READ) {
                return ArtifactManagementResult.blocked("artifact_missing", "missing", Map.of());
            }
            if (request.artifactType() == ArtifactType.RUN_RESULT && request.action() == ArtifactAction.UPSERT) {
                return ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of());
            }
            return ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of(
                    "artifact", artifact(request.input().has("planName"))));
        };
    }

    private ArtifactManagementFeature statefulArtifacts(Map<String, JsonNode> runs) {
        return request -> {
            if (request.artifactType() == ArtifactType.RUN_RESULT && request.action() == ArtifactAction.READ) {
                JsonNode artifact = runs.get(runKey(request.input()));
                return artifact == null ? ArtifactManagementResult.blocked("artifact_missing", "missing", Map.of())
                        : ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of("artifact", artifact));
            }
            if (request.artifactType() == ArtifactType.RUN_RESULT && request.action() == ArtifactAction.UPSERT) {
                runs.put(runKey(request.input()), request.input().path("payload"));
                return ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of());
            }
            return ArtifactManagementResult.success(request.artifactType(), request.action(), Map.of(
                    "artifact", artifact(request.input().has("planName"))));
        };
    }

    private static String runKey(JsonNode input) {
        return input.path("planName").asText() + ":" + input.path("runId").asText();
    }

    private static Map<String, Object> runDetails() {
        return Map.of("runStatus", "pass", "suiteContext", Map.of(
                "traceId", "trace-from-plan", "authorization", "secret", "container", Map.of("token", "secret")));
    }

    private static ExecutionRuntimeLifecycle lifecycle(AtomicInteger cleanupCalls) {
        return new ExecutionRuntimeLifecycle() {
            @Override
            public RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request) {
                return new RuntimeLifecycleResult(true, true, "attached", Map.of("pid", "123", "status", "attached"));
            }

            @Override
            public RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request) {
                cleanupCalls.incrementAndGet();
                return new RuntimeLifecycleResult(true, true, "cleaned", Map.of("status", "terminal"));
            }
        };
    }

    private JsonNode artifact(boolean plan) {
        if (plan) {
            ObjectNode artifact = mapper.createObjectNode();
            artifact.putObject("metadata");
            artifact.putObject("contract");
            return artifact;
        }
        ObjectNode artifact = mapper.createObjectNode();
        ObjectNode profile = artifact.putArray("workspaces").addObject().putArray("executionProfiles").addObject();
        profile.put("executionProfile", "nightly");
        profile.put("suiteType", "performance");
        profile.put("executionPolicy", "stop_on_fail");
        var plans = profile.putArray("plans");
        var first = plans.addObject().put("order", 1).put("planName", "perf-smoke").put("onFail", "inherit");
        first.putObject("providedContext").put("traceId", "trace-1").put("authorization", "secret").putObject("container").put("token", "secret");
        plans.addObject().put("order", 2).put("planName", "perf-load").put("onFail", "inherit");
        return artifact;
    }
}
