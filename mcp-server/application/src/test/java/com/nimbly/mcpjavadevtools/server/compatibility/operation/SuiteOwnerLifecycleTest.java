package com.nimbly.mcpjavadevtools.server.compatibility.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.DefaultArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.DefaultExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.impl.ExecuteExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.FileExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.ExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.operation.ExecutionOrchestrationOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmMutationResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.DefaultPerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl.ExecutePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.DefaultRegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.ExecuteRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.PreflightRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.DefaultSecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.impl.ExecuteSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.lifecycle.FileExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.lifecycle.ApplicationExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/** Product persistence and cleanup evidence when orchestration times out inside a real Suite owner. */
class SuiteOwnerLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OperationId ORCHESTRATION = OperationId.of("execution_orchestration.execute");

    @TempDir
    Path workspace;
    private final List<Process> ownedProcesses = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopRemainingOwnedProcesses() {
        for (Process process : ownedProcesses) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"regression", "performance", "security"})
    void everyRealSuiteOwnerTimeoutPersistsCleanupAndAllowsResume(String suiteType) throws Exception {
        writeProfile(suiteType);
        OwnerGate gate = new OwnerGate();
        RecordingLifecycle lifecycle = new RecordingLifecycle(ownedProcesses);
        OperationDirectory directory = directory(gate, lifecycle, 1_000);
        String suiteRunId = "timeout-" + suiteType;
        OperationInvocation invocation = invocation(suiteType + "-smoke", suiteRunId);
        AtomicReference<OperationExecutionResult> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(directory.execute(invocation)));

        caller.start();
        assertThat(gate.started.await(3, TimeUnit.SECONDS)).as("result=%s", result.get()).isTrue();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.get().reasonCode()).isEqualTo("operation_timeout");
        assertThat(gate.interruptedSignal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.interrupted).isTrue();
        assertThat(lifecycle.cleaned.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.cleanupResult.get().successful()).isTrue();
        assertThat(lifecycle.process.isAlive()).isFalse();
        Path suite = workspace.resolve(".mcpjvm/demo/suite-runs").resolve(suiteRunId);
        awaitTerminalState(suite);
        assertThat(suite.resolve("execution.lock")).doesNotExist();

        gate.release.countDown();
        OperationExecutionResult resumed = directory.execute(invocation);
        assertThat(resumed.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(resumed.result().path("status").asText()).as(resumed.result().toString()).isEqualTo("pass");
        JsonNode persisted = JSON.readTree(Files.readString(suite.resolve("execution_orchestration.result.json")));
        assertThat(persisted.path("suiteRunId").asText()).isEqualTo(suiteRunId);
        assertThat(persisted.path("planRuns").toString()).contains(suiteType + "-smoke");
        assertPersistedRunArtifact(suiteType, suiteRunId, persisted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"regression", "performance", "security"})
    void everyRealSuiteOwnerCallerInterruptionPersistsCleanupAndAllowsResume(String suiteType)
            throws Exception {
        writeProfile(suiteType);
        OwnerGate gate = new OwnerGate();
        RecordingLifecycle lifecycle = new RecordingLifecycle(ownedProcesses);
        OperationDirectory directory = directory(gate, lifecycle, 5_000);
        String suiteRunId = "interrupted-" + suiteType;
        OperationInvocation invocation = invocation(suiteType + "-smoke", suiteRunId);
        AtomicReference<OperationExecutionResult> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(directory.execute(invocation)));

        caller.start();
        assertThat(gate.started.await(3, TimeUnit.SECONDS)).as("result=%s", result.get()).isTrue();
        caller.interrupt();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(result.get().reasonCode()).isEqualTo("operation_caller_interrupted");
        assertThat(gate.interruptedSignal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.interrupted).isTrue();
        assertThat(lifecycle.cleaned.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.cleanupResult.get().successful()).isTrue();
        assertThat(lifecycle.process.isAlive()).isFalse();
        Path suite = workspace.resolve(".mcpjvm/demo/suite-runs").resolve(suiteRunId);
        awaitTerminalState(suite);
        assertThat(suite.resolve("execution.lock")).doesNotExist();

        gate.release.countDown();
        OperationExecutionResult resumed = directory.execute(invocation);
        assertThat(resumed.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(resumed.result().path("status").asText()).as(resumed.result().toString()).isEqualTo("pass");
        JsonNode persisted = JSON.readTree(Files.readString(suite.resolve("execution_orchestration.result.json")));
        assertPersistedRunArtifact(suiteType, suiteRunId, persisted);
    }

    private void writeProfile(String suiteType) throws Exception {
        Path project = workspace.resolve(".mcpjvm/demo");
        Path plan = project.resolve("plans").resolve(suiteType).resolve(suiteType + "-smoke");
        Files.createDirectories(plan);
        Files.writeString(project.resolve("projects.json"), """
                {"workspaces":[{"projectRoot":%s,
                  "defaults":{"orchestrator":{"resumePollMax":1,
                  "resumePollIntervalMs":10,"resumePollTimeoutMs":100}},
                  "executionProfiles":[{"executionProfile":"%s-smoke",
                  "executionPolicy":"stop_on_fail","suiteType":"%s",
                  "plans":[{"order":1,"planName":"%s-smoke"}]}]}]}
                """.formatted(JSON.writeValueAsString(workspace.toString()),
                        suiteType, suiteType, suiteType));
        if ("performance".equals(suiteType)) {
            writePerformancePlan(plan);
        } else if ("security".equals(suiteType)) {
            writeSecurityPlan(plan);
        } else {
            writeRegressionPlan(plan);
        }
    }

    private static void writeRegressionPlan(Path plan) throws Exception {
        Files.writeString(plan.resolve("metadata.json"), """
                {"specVersion":"1.0.0","suiteType":"regression",
                 "execution":{"intent":"regression","probeVerification":false,
                 "pinStrictProbeKey":false,"discoveryPolicy":"allow_discoverable_prerequisites"}}
                """);
        Files.writeString(plan.resolve("contract.json"), """
                {"targets":[{"type":"class_method","selectors":{"fqcn":"example.Target",
                "method":"run","sourceRoot":"src/main/java"}}],"prerequisites":[],
                "steps":[{"order":1,"id":"transport","targetRef":0,"protocol":"http",
                "transport":{"http":{"method":"GET","url":"http://127.0.0.1:8080/transport"}},
                "expect":[{"id":"status","actualPath":"response.statusCode",
                "operator":"field_equals","expected":200}]}]}
                """);
    }

    private void writePerformancePlan(Path plan) throws Exception {
        Path executable = workspace.resolve("jmeter");
        Files.writeString(executable, "fixture");
        Files.writeString(plan.resolve("metadata.json"), """
                {"specVersion":"1.0.0","suiteType":"performance",
                 "execution":{"intent":"performance"}}
                """);
        Files.writeString(plan.resolve("contract.json"), """
                {"workloadProvider":{"type":"jmeter","mode":"generated_http",
                "options":{"installationPath":%s}},
                "entrypoints":[{"transport":{"protocol":"http","baseUrl":"http://127.0.0.1",
                "healthCheckPath":"/health"},"request":{"method":"GET","path":"/workload"}}],
                "loadModel":{"mode":"concurrency","concurrency":1,"rampUpSeconds":0,"durationSeconds":1},
                "observationTargets":{"probeBaseUrl":"http://127.0.0.1",
                "requiredLineHits":["example.Target#run:1"]},
                "successCriteria":{"maxErrorRatePct":0,"minThroughputPerSec":0.1,"p95LatencyMs":100}}
                """.formatted(JSON.writeValueAsString(executable.toString())));
    }

    private static void writeSecurityPlan(Path plan) throws Exception {
        Files.writeString(plan.resolve("metadata.json"), """
                {"specVersion":"1.0.0","suiteType":"security",
                 "execution":{"intent":"security"}}
                """);
        Files.writeString(plan.resolve("contract.json"), """
                {"suiteType":"security","securityMode":"blackbox",
                "targetBoundary":{"environment":"local-ci","baseUrl":"http://127.0.0.1:8080",
                "allowedHosts":["127.0.0.1"],"allowedPorts":[8080],
                "externalNetworkAccess":"forbidden"},
                "entrypoints":[{"id":"transport","transport":{"type":"http",
                "method":"GET","path":"/"},"baseline":{}}],
                "authenticationProfiles":[{"id":"anonymous","kind":"anonymous"}],
                "customCases":[],
                "exhaustiveness":{"mode":"finite_matrix","requireAllCases":true,
                "onIncomplete":"blocked"},
                "safetyPolicy":{"maxConcurrency":1,"maxRequestsPerSecond":1,
                "maxDurationMs":1000,"destructivePayloads":"forbidden",
                "stateMutation":"test-tenant-only","cleanupRequired":true},
                "verdictPolicy":{"failOnSeverity":["critical"],
                "requireExhaustiveCompletion":true,"blockedCountsAs":"fail"}}
                """);
    }

    private static RegressionSuiteFeature regression(OwnerGate gate) {
        TransportExecutionFeature transport = request -> gate.execute();
        RegressionPlanPreflight preflight = new RegressionPlanPreflight();
        return new DefaultRegressionSuiteFeature(List.of(
                new PreflightRegressionPlanAction(preflight),
                new ExecuteRegressionPlanAction(new RegressionPlanExecutor(preflight, transport, JSON))));
    }

    private static DefaultPerformanceSuiteFeature performance(OwnerGate gate) {
        var workload = new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(),
                (executable, directory, jmx, jtl, log, timeout) -> gate.executeWorkload(jtl, log),
                new JmeterJtlCollector());
        ProbeFeature probe = request -> {
            if (request instanceof ProbeWaitForHitRequest wait) {
                return ProbeResult.success(new ProbeWaitForHitResult(
                        wait.keySelector().key(), ProbeWaitOutcome.LINE_HIT, 1, 0L, 1L, null, null));
            }
            return ProbeResult.success();
        };
        return new DefaultPerformanceSuiteFeature(List.of(new ExecutePerformancePlanAction(
                new PerformancePlanExecutor(
                        new JmeterExecutableResolver(), workload, probe, request -> successfulTransport()))));
    }

    private static DefaultSecuritySuiteFeature security(OwnerGate gate) {
        return new DefaultSecuritySuiteFeature(List.of(new ExecuteSecurityPlanAction(
                new SecurityPlanExecutor(request -> gate.execute(), new SecurityKnowledgeCatalog()))));
    }

    private static ExecuteTransportResult successfulTransport() {
        return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "{}", 1);
    }

    private OperationDirectory directory(OwnerGate gate, ExecutionRuntimeLifecycle lifecycle, long timeoutMillis) {
        WorkspaceContext context = new WorkspaceContext(
                new DefaultApplicationArguments("--workspace-root=" + workspace));
        var action = new ExecuteExecutionOrchestrationAction(
                artifacts(), performance(gate), security(gate), regression(gate), JSON,
                (project, type, plan, run) -> Optional.of(workspace.resolve(".mcpjvm").resolve(project)
                        .resolve("plans").resolve(type).resolve(plan).resolve("runs").resolve(run).toString()),
                new FileExecutionRunLease(workspace), new FileExecutionSuiteStateStore(context, JSON), lifecycle);
        var feature = new DefaultExecutionOrchestrationFeature(List.of(action));
        var registration = ExecutionOrchestrationOperationRegistrations.create(feature, JSON).get(0);
        var timed = withTimeout(registration, timeoutMillis);
        var builtIn = OperationManifestLoader.loadBuiltIn();
        return new OperationDirectory(List.of(timed), new OperationManifestDocument(
                builtIn.version(), Map.of(ORCHESTRATION, builtIn.documentation(ORCHESTRATION))), JSON);
    }

    private ArtifactManagementFeature artifacts() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> Optional.of(workspace), new ArtifactJsonStore(JSON),
                new SqliteRunStateStore(JSON), JSON);
        OperationExposure exposure = new OperationExposure(
                ArtifactOperationCatalog.TOOL_NAME, "ArtifactManagementMcpTool",
                Arrays.stream(ArtifactManagementAction.values()).map(ArtifactManagementAction::routeId).toList());
        return new DefaultArtifactManagementFeature(new ArtifactOperationCatalog(
                new ProbeConfigOperations(support), new ProjectContextOperations(support),
                new PlanOperations(support), new RunResultOperations(support),
                new ExecutionExportOperations(support), exposure));
    }

    private static OperationInvocation invocation(String profile, String suiteRunId) {
        return new OperationInvocation(ORCHESTRATION, JSON.createObjectNode()
                .put("projectName", "demo").put("executionProfile", profile)
                .put("suiteRunId", suiteRunId), true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OperationRegistration<?, ?> withTimeout(
            OperationRegistration<?, ?> registration, long timeoutMillis) {
        OperationSafetyPolicy original = registration.safety();
        OperationSafetyPolicy bounded = new OperationSafetyPolicy(
                original.sideEffect(), original.confirmationRequired(), original.credentialPolicy(),
                original.redactionPolicy(), timeoutMillis, original.cancellationSupported(),
                original.maxInputBytes(), original.maxOutputBytes());
        return new OperationRegistration(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), bounded),
                registration.decoder(), registration.executor(), registration.encoder(),
                registration.operationCatalog(), registration.provenance());
    }

    private static void awaitTerminalState(Path suite) throws Exception {
        Path state = suite.resolve("execution_orchestration.result.json");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(state)) {
                JsonNode persisted = JSON.readTree(Files.readString(state));
                if ("terminal".equals(persisted.path("runtimeLifecycle").path("status").asText())) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        assertThat(state).as("terminal cleanup checkpoint").isRegularFile();
        assertThat(JSON.readTree(Files.readString(state)).path("runtimeLifecycle").path("status").asText())
                .isEqualTo("terminal");
    }

    private void assertPersistedRunArtifact(String suiteType, String suiteRunId, JsonNode checkpoint)
            throws Exception {
        JsonNode planRun = checkpoint.path("planRuns").get(0);
        assertThat(planRun).as(checkpoint.toString()).isNotNull();
        String runId = planRun.path("runId").asText();
        Path run = workspace.resolve(".mcpjvm/demo/plans").resolve(suiteType)
                .resolve(suiteType + "-smoke").resolve("runs").resolve(runId);
        Path result = run.resolve("execution.result.json");
        assertThat(result).isRegularFile();
        JsonNode artifact = JSON.readTree(Files.readString(result));
        assertThat(artifact.path("suiteRunId").asText()).isEqualTo(suiteRunId);
        assertThat(artifact.path("executionProfile").asText()).isEqualTo(suiteType + "-smoke");
        assertThat(artifact.path("status").asText()).isEqualTo("pass");
        if ("performance".equals(suiteType)) {
            assertThat(run.resolve("workload.jmeter.jmx")).isRegularFile();
            assertThat(run.resolve("workload.jmeter.jtl")).isRegularFile();
            assertThat(run.resolve("workload.jmeter.log")).isRegularFile();
        }
    }

    private static final class OwnerGate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interruptedSignal = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();

        private ExecuteTransportResult execute() {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "{}", 1);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
                return ExecuteTransportResult.blockedRuntime("transport_interrupted", "cancelled", "http", 1);
            }
        }

        private int executeWorkload(Path jtl, Path log) throws IOException {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                Files.writeString(jtl, "elapsed,success\n10,true\n");
                Files.writeString(log, "fixture JMeter completed");
                return 0;
            } catch (InterruptedException exception) {
                interrupted.set(true);
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    private static final class RecordingLifecycle implements ExecutionRuntimeLifecycle {
        private final CountDownLatch cleaned = new CountDownLatch(1);
        private final AtomicReference<RuntimeLifecycleResult> cleanupResult = new AtomicReference<>();
        private final List<Process> processes;
        private Process process;
        private long started;
        private final ApplicationExecutionRuntimeLifecycle production =
                new ApplicationExecutionRuntimeLifecycle(this::jvmAction, request -> ProbeResult.success(), JSON);

        private RecordingLifecycle(List<Process> processes) {
            this.processes = processes;
        }

        @Override
        public RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request) {
            try {
                Path java = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
                Path classes = Path.of(SuiteOwnerLifecycleTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                process = new ProcessBuilder(java.toString(), "-cp", classes.toString(),
                        OwnedRuntimeMain.class.getName()).start();
                processes.add(process);
                started = process.info().startInstant().orElseThrow().toEpochMilli();
                return new RuntimeLifecycleResult(true, true, "attached", Map.of(
                        "status", "attached", "pid", Long.toString(process.pid()),
                        "processStartEpochMs", started));
            } catch (Exception exception) {
                return RuntimeLifecycleResult.blocked("runtime_start_failed", Map.of());
            }
        }

        @Override
        public RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request) {
            RuntimeLifecycleResult result = production.cleanup(request);
            cleanupResult.set(result);
            cleaned.countDown();
            return result;
        }

        private JvmLifecycleResult jvmAction(
                com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest request) {
            if (request instanceof ListJvmsRequest) {
                return JvmLifecycleResult.discovery("ok", new JvmListResult(List.of(new JvmCandidate(
                        Long.toString(process.pid()), "owned test runtime", "test", "java", List.of(), started))));
            }
            return JvmLifecycleResult.mutation(JvmLifecycleResultStatus.OK, "ok", new JvmMutationResult(
                    "deactivate", "deactivated", Long.toString(process.pid()), started, null, null, List.of()));
        }
    }

    /** Bounded process whose real exit is verified by the production runtime cleanup adapter. */
    public static class OwnedRuntimeMain {
        public static void main(String[] args) throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }
}
