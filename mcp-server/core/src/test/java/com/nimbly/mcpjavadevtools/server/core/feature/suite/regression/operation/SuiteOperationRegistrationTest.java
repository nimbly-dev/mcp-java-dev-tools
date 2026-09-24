package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.TrustedDirectSuiteRun;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.DefaultPerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.PerformanceSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl.ExecutePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation.PerformanceSuiteOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation.TrustedPerformanceSuiteRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.DefaultRegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.ExecuteRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.PreflightRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.DefaultSecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.SecuritySuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.impl.ExecuteSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation.SecuritySuiteOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation.TrustedSecuritySuiteRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestAssembler;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenanceKind;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SuiteOperationRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> IDS = List.of(
            "regression_suite.execute_plan",
            "regression_suite.preflight",
            "performance_suite.execute_plan",
            "security_suite.execute_plan");
    private static final List<String> EXECUTE_IDS = List.of(
            "regression_suite.execute_plan",
            "performance_suite.execute_plan",
            "security_suite.execute_plan");

    @TempDir
    Path workspace;

    @Test
    void registersExactlyFourDirectOperationsWithOrchestrationProvenance() {
        List<OperationRegistration<?, ?>> registrations = registrations(new AtomicReference<>());

        assertThat(registrations).extracting(registration ->
                registration.descriptor().operationId().value()).containsExactlyInAnyOrderElementsOf(IDS);
        assertThat(registrations).allSatisfy(registration -> {
            assertThat(registration.provenance().kind())
                    .isEqualTo(OperationProvenanceKind.NEW_DIRECT_CDE_OPERATION);
            assertThat(registration.provenance().invocationTool()).isEqualTo("execution_orchestration");
            assertThat(registration.provenance().invocationAction()).isEqualTo("execute");
            assertThat(registration.provenance().discriminators())
                    .containsKey("suiteType").containsKey("suiteAction");
            assertThat(registration.compatibility())
                    .containsEntry("provenanceKind", "NEW_DIRECT_CDE_OPERATION")
                    .doesNotContainKeys("releasedTool", "releasedAction", "actionless");
            assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                    .isEqualTo(OperationCancellationState.NOT_CANCELLABLE);
            assertThat(OperationCancellationSupport.guarantee(registration.executor()))
                    .isEqualTo(OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION);
            assertThat(registration.safety().cancellationSupported()).isFalse();
        });
    }

    @Test
    void acceptsAnySuiteFeatureImplementationWithoutHiddenDefaultDowncast() {
        var regression = RegressionSuiteOperationRegistrations.create(
                request -> RegressionSuiteResult.ready(Map.of("action", request.action().name())), JSON);
        var performance = PerformanceSuiteOperationRegistrations.create(
                request -> PerformanceSuiteResult.completed(Map.of("owner", "alternate")), JSON);
        var security = SecuritySuiteOperationRegistrations.create(
                request -> SecuritySuiteResult.completed(Map.of("owner", "alternate")), JSON);

        assertThat(regression).hasSize(2);
        assertThat(performance).hasSize(1);
        assertThat(security).hasSize(1);
    }

    @Test
    void everyExecuteTimeoutLetsTheRealOwnerCompleteWithoutInterruption()
            throws Exception {
        for (String id : EXECUTE_IDS) {
            OwnerProbe probe = new OwnerProbe();
            OperationRegistration<?, ?> registration = byId(realOwnerRegistrations(probe)).get(id);
            Path evidence = workspace.resolve("timeout").resolve(id + ".json");

            assertTimedContinuation(observe(withTimeout(registration, 100), evidence, probe), id, probe);
            assertContinuedOwnerEvidence(id, evidence, probe);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "regression_suite.execute_plan",
            "performance_suite.execute_plan",
            "security_suite.execute_plan"})
    void immediateRetryCannotOverlapAnInProgressRealOwner(String id) throws Exception {
        OwnerProbe probe = new OwnerProbe();
        OperationRegistration<?, ?> registration = withTimeout(
                byId(realOwnerRegistrations(probe)).get(id), 100);
        OperationDirectory directory = directory(registration);
        OperationInvocation invocation = new OperationInvocation(OperationId.of(id), input(id), true);

        var timedOut = directory.execute(invocation);
        assertThat(timedOut.status()).as(id).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(probe.started.await(1, TimeUnit.SECONDS)).as(id).isTrue();

        var inProgress = directory.execute(invocation);
        assertThat(inProgress.result().path("reasonCode").asText())
                .as(id).isEqualTo("direct_suite_run_in_progress");
        assertThat(probe.ownerCalls.get()).as(id).isEqualTo(1);

        probe.release.countDown();
        String suite = id.substring(0, id.indexOf('_'));
        Path artifact = workspace.resolve(".mcpjvm/demo/plans").resolve(suite)
                .resolve(suite + "-smoke/runs/direct-" + suite + "-1/execution.result.json");
        awaitRunArtifact(artifact);
        String persisted = Files.readString(artifact);
        assertThat(JSON.readTree(persisted).path("directResult").path("reasonCode").asText())
                .as(id).isEqualTo("ok");
        int completedCalls = probe.ownerCalls.get();

        var resumed = directory.execute(invocation);
        assertThat(resumed.result().path("reasonCode").asText()).as(id).isEqualTo("ok");
        assertThat(probe.ownerCalls.get()).as(id).isEqualTo(completedCalls);
        assertThat(Files.readString(artifact)).as(id).isEqualTo(persisted);
    }

    private static void awaitRunArtifact(Path artifact) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.isRegularFile(artifact) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(artifact).isRegularFile();
    }

    @Test
    void callerInterruptionLetsEveryRealExecuteOwnerCompleteWithoutInterruption()
            throws Exception {
        for (String id : EXECUTE_IDS) {
            OwnerProbe probe = new OwnerProbe();
            OperationRegistration<?, ?> registration = byId(realOwnerRegistrations(probe)).get(id);
            Path evidence = workspace.resolve("caller-interruption").resolve(id + ".json");
            OperationDirectory directory = directory(observe(registration, evidence, probe));
            AtomicReference<com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult>
                    result = new AtomicReference<>();
            Thread caller = new Thread(() -> result.set(directory.execute(new OperationInvocation(
                    OperationId.of(id), input(id), true))), "mcpjvm-623-interrupted-caller");

            caller.start();
            assertThat(probe.started.await(1, TimeUnit.SECONDS)).as(id).isTrue();
            caller.interrupt();
            caller.join(2_000);

            assertThat(caller.isAlive()).as(id).isFalse();
            assertThat(result.get().status()).as(id).isEqualTo(OperationExecutionStatus.CANCELLED);
            assertThat(result.get().reasonCode()).as(id).isEqualTo("operation_caller_interrupted");
            probe.release.countDown();
            assertContinuedOwnerEvidence(id, evidence, probe);
        }
    }

    private void assertTimedContinuation(
            OperationRegistration<?, ?> registration, String id, OwnerProbe probe) throws Exception {
        OperationId operationId = registration.descriptor().operationId();
        var result = directory(registration).execute(new OperationInvocation(
                operationId, input(id), true));

        assertThat(probe.started.await(1, TimeUnit.SECONDS)).as(id).isTrue();
        assertThat(result.status()).as(id).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).as(id).isEqualTo("operation_timeout");
        probe.release.countDown();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OperationRegistration<?, ?> withTimeout(
            OperationRegistration<?, ?> registration, long timeoutMillis) {
        OperationSafetyPolicy original = registration.safety();
        OperationSafetyPolicy bounded = new OperationSafetyPolicy(
                original.sideEffect(), original.confirmationRequired(), original.credentialPolicy(),
                original.redactionPolicy(), timeoutMillis, false,
                original.maxInputBytes(), original.maxOutputBytes());
        return new OperationRegistration(registration.descriptor(), registration.requestType(),
                registration.resultType(), new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), bounded),
                registration.decoder(), registration.executor(), registration.encoder(),
                registration.operationCatalog(), registration.provenance());
    }

    @Test
    void strictManifestJoinAcceptsDirectOperationsWithoutReleasedAliases() {
        List<OperationRegistration<?, ?>> registrations = registrations(new AtomicReference<>());
        var builtIn = OperationManifestLoader.loadBuiltIn();
        Map<OperationId, com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation>
                documents = new LinkedHashMap<>();
        for (String id : IDS) {
            OperationId operationId = OperationId.of(id);
            documents.put(operationId, builtIn.documentation(operationId));
        }

        var manifest = OperationManifestAssembler.assembleStrict(
                registrations, new OperationManifestDocument(builtIn.version(), documents));

        assertThat(manifest.registrations()).hasSize(4);
        assertThat(manifest.descriptors()).allSatisfy(descriptor ->
                assertThat(descriptor.documentation().aliases()).isEmpty());
    }

    @Test
    void schemasDistinguishRegressionPreflightAndExecutionAndRejectUnknownFields() {
        Map<String, OperationRegistration<?, ?>> registrations = byId(registrations(new AtomicReference<>()));
        var preflight = registrations.get("regression_suite.preflight");
        var execute = registrations.get("regression_suite.execute_plan");
        ObjectNode valid = input("regression_suite.preflight");

        assertThat(preflight.inputSchema().definition().path("properties").has("runtimeContextName")).isFalse();
        assertThat(execute.safety().sideEffect()).isEqualTo("filesystem_write");
        assertThat(preflight.safety().sideEffect()).isEqualTo("filesystem_read");
        assertThat(OperationSchemaValidator.violations(preflight.inputSchema(), valid)).isEmpty();
        valid.put("runDirectory", "C:/arbitrary");
        assertThat(OperationSchemaValidator.violations(preflight.inputSchema(), valid)).isNotEmpty();
        valid.remove("runDirectory");
        valid.put("unknown", true);
        assertThat(OperationSchemaValidator.violations(preflight.inputSchema(), valid)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "regression_suite.preflight",
            "regression_suite.execute_plan",
            "performance_suite.execute_plan",
            "security_suite.execute_plan"})
    void directoryRejectsInvalidOversizedAndCallerControlledExecutionInputs(String id) {
        OperationRegistration<?, ?> registration = byId(registrations(new AtomicReference<>())).get(id);
        OperationDirectory directory = directory(registration);
        OperationId operationId = OperationId.of(id);
        var missing = directory.execute(new OperationInvocation(operationId, JSON.createObjectNode(), true));
        assertThat(missing.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(missing.reasonCode()).isEqualTo("operation_input_schema_invalid");

        ObjectNode unsafe = input(id);
        unsafe.put("runDirectory", workspace.resolve("outside").toString());
        unsafe.putObject("contract").putObject("workloadProvider")
                .putObject("options").put("installationPath", workspace.resolve("arbitrary.exe").toString());
        var rejected = directory.execute(new OperationInvocation(operationId, unsafe, true));
        assertThat(rejected.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(rejected.reasonCode()).isEqualTo("operation_input_schema_invalid");

        ObjectNode oversized = input(id);
        oversized.put("noise", "x".repeat(registration.safety().maxInputBytes() + 1));
        assertThatThrownBy(() -> new OperationInvocation(operationId, oversized, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safety bound");
    }

    @Test
    void routesAllFourOperationsToTheirExactSubstantiveOwners() {
        AtomicReference<RegressionSuiteAction> invoked = new AtomicReference<>();
        Map<String, OperationRegistration<?, ?>> registrations = byId(registrations(invoked));
        ObjectNode input = input("regression_suite.execute_plan");

        assertThat(registrations.get("regression_suite.preflight").execute(input).path("status").asText())
                .isEqualTo("ready");
        assertThat(invoked).hasValue(RegressionSuiteAction.PREFLIGHT);
        assertThat(registrations.get("regression_suite.execute_plan").execute(input).path("status").asText())
                .isEqualTo("ready");
        assertThat(invoked).hasValue(RegressionSuiteAction.EXECUTE_PLAN);
        ObjectNode suiteInput = input("performance_suite.execute_plan");
        assertThat(registrations.get("performance_suite.execute_plan")
                .execute(suiteInput).path("status").asText()).isEqualTo("completed");
        assertThat(registrations.get("security_suite.execute_plan")
                .execute(input("security_suite.execute_plan")).path("status").asText()).isEqualTo("completed");
    }

    private List<OperationRegistration<?, ?>> registrations(
            AtomicReference<RegressionSuiteAction> invoked) {
        var regression = new DefaultRegressionSuiteFeature(List.of(
                regressionHandler(RegressionSuiteAction.PREFLIGHT, invoked),
                regressionHandler(RegressionSuiteAction.EXECUTE_PLAN, invoked)));
        var performance = new DefaultPerformanceSuiteFeature(List.of(performanceHandler()));
        var security = new DefaultSecuritySuiteFeature(List.of(securityHandler()));
        return java.util.stream.Stream.of(
                TrustedRegressionSuiteRegistrations.create(regression, JSON, trusted()),
                TrustedPerformanceSuiteRegistrations.create(performance, JSON, trusted()),
                TrustedSecuritySuiteRegistrations.create(security, JSON, trusted()))
                .flatMap(List::stream).toList();
    }

    private static RegressionSuiteActionHandler regressionHandler(
            RegressionSuiteAction action, AtomicReference<RegressionSuiteAction> invoked) {
        return new RegressionSuiteActionHandler() {
            @Override
            public RegressionSuiteAction action() {
                return action;
            }

            @Override
            public RegressionSuiteResult execute(RegressionSuiteRequest request) {
                invoked.set(request.action());
                return RegressionSuiteResult.ready(Map.of("ownerAction", request.action().name()));
            }
        };
    }

    private static PerformanceSuiteActionHandler performanceHandler() {
        return new PerformanceSuiteActionHandler() {
            @Override
            public PerformanceSuiteAction action() {
                return PerformanceSuiteAction.EXECUTE_PLAN;
            }

            @Override
            public PerformanceSuiteResult execute(PerformanceSuiteRequest request) {
                return PerformanceSuiteResult.completed(Map.of("thresholdStatus", "pass"));
            }
        };
    }

    private static SecuritySuiteActionHandler securityHandler() {
        return new SecuritySuiteActionHandler() {
            @Override
            public SecuritySuiteAction action() {
                return SecuritySuiteAction.EXECUTE_PLAN;
            }

            @Override
            public SecuritySuiteResult execute(SecuritySuiteRequest request) {
                return SecuritySuiteResult.completed(Map.of("coverageComplete", true));
            }
        };
    }

    private List<OperationRegistration<?, ?>> realOwnerRegistrations(OwnerProbe probe) {
        RegressionPlanPreflight preflight = new RegressionPlanPreflight();
        TransportExecutionFeature transport = request -> probe.executeTransport();
        var regression = new DefaultRegressionSuiteFeature(List.of(
                new PreflightRegressionPlanAction(preflight),
                new ExecuteRegressionPlanAction(new RegressionPlanExecutor(preflight, transport, JSON))));
        var workload = new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(),
                (executable, directory, jmx, jtl, log, timeout) -> probe.executeWorkload(jtl, log),
                new JmeterJtlCollector());
        ProbeFeature liveProbe = request -> {
            if (request instanceof ProbeWaitForHitRequest wait) {
                return ProbeResult.success(new ProbeWaitForHitResult(
                        wait.keySelector().key(), ProbeWaitOutcome.LINE_HIT, 1, 0L, 1L, null, null));
            }
            return ProbeResult.success();
        };
        var performance = new DefaultPerformanceSuiteFeature(List.of(
                new ExecutePerformancePlanAction(
                        new PerformancePlanExecutor(
                                new JmeterExecutableResolver(), workload, liveProbe,
                                request -> successfulTransport()))));
        var security = new DefaultSecuritySuiteFeature(List.of(
                new ExecuteSecurityPlanAction(new SecurityPlanExecutor(
                        transport, new SecurityKnowledgeCatalog()))));
        return java.util.stream.Stream.of(
                TrustedRegressionSuiteRegistrations.create(regression, JSON, trusted()),
                TrustedPerformanceSuiteRegistrations.create(performance, JSON, trusted()),
                TrustedSecuritySuiteRegistrations.create(security, JSON, trusted()))
                .flatMap(List::stream).toList();
    }

    private static ExecuteTransportResult successfulTransport() {
        return ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of(), "{\"status\":\"ok\"}", 1);
    }

    private ObjectNode input(String id) {
        String suite = id.substring(0, id.indexOf('_'));
        return JSON.createObjectNode().put("projectName", "demo")
                .put("executionProfile", suite + "-smoke")
                .put("planName", suite + "-smoke")
                .put("suiteRunId", "direct-" + suite);
    }

    private TrustedDirectSuiteRun trusted() {
        try {
            Path project = workspace.resolve(".mcpjvm/demo");
            Files.createDirectories(project);
            ObjectNode contexts = JSON.createObjectNode();
            ObjectNode selected = contexts.putArray("workspaces").addObject();
            selected.put("projectRoot", workspace.toString());
            selected.putObject("defaults").putObject("orchestrator")
                    .put("resumePollMax", 1).put("resumePollIntervalMs", 10).put("resumePollTimeoutMs", 100);
            var profiles = selected.putArray("executionProfiles");
            writePlan(project, profiles, "regression", regressionInput());
            writePlan(project, profiles, "performance", performanceInput());
            writePlan(project, profiles, "security", securityInput());
            Files.writeString(project.resolve("projects.json"), contexts.toString());
            ArtifactManagementSupport support = new ArtifactManagementSupport(
                    () -> Optional.of(workspace), new ArtifactJsonStore(JSON), new SqliteRunStateStore(JSON), JSON);
            ArtifactOperationCatalog artifacts = new ArtifactOperationCatalog(
                    new ProbeConfigOperations(support), new ProjectContextOperations(support),
                    new PlanOperations(support), new RunResultOperations(support),
                    new ExecutionExportOperations(support), new OperationExposure(
                            ArtifactOperationCatalog.TOOL_NAME, "ArtifactManagementMcpTool",
                            Arrays.stream(ArtifactManagementAction.values())
                                    .map(ArtifactManagementAction::routeId).toList()));
            return new TrustedDirectSuiteRun(artifacts,
                    (name, type, plan, run) -> Optional.of(project.resolve("plans").resolve(type)
                            .resolve(plan).resolve("runs").resolve(run).toString()), JSON);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void writePlan(Path project, com.fasterxml.jackson.databind.node.ArrayNode profiles,
            String type, ObjectNode input) throws IOException {
        String name = type + "-smoke";
        Path plan = project.resolve("plans").resolve(type).resolve(name);
        Files.createDirectories(plan);
        Files.writeString(plan.resolve("metadata.json"), input.path("metadata").toString());
        Files.writeString(plan.resolve("contract.json"), input.path("contract").toString());
        profiles.addObject().put("executionProfile", name).put("suiteType", type)
                .put("executionPolicy", "stop_on_fail")
                .putArray("plans").addObject().put("order", 1).put("planName", name);
    }

    private static ObjectNode regressionInput() {
        ObjectNode input = JSON.createObjectNode();
        input.putObject("metadata").putObject("execution").put("intent", "regression");
        ObjectNode contract = input.putObject("contract");
        contract.putArray("targets").addObject().put("type", "class_method")
                .putObject("selectors").put("fqcn", "example.Target").put("method", "run");
        contract.putArray("prerequisites");
        ObjectNode step = contract.putArray("steps").addObject();
        step.put("order", 1).put("id", "transport").put("protocol", "http").put("targetRef", 0);
        step.putObject("transport").putObject("http")
                .put("method", "GET").put("url", "http://127.0.0.1/");
        step.putArray("expect").addObject().put("id", "status")
                .put("actualPath", "response.statusCode").put("operator", "field_equals")
                .put("expected", 200);
        return input;
    }

    private ObjectNode performanceInput() {
        Path executable = workspace.resolve("jmeter");
        try {
            Files.writeString(executable, "fixture");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        ObjectNode input = JSON.createObjectNode();
        input.putObject("metadata");
        input.put("runDirectory", workspace.resolve("performance-run").toString());
        ObjectNode contract = input.putObject("contract");
        contract.putObject("workloadProvider").put("type", "jmeter").put("mode", "generated_http")
                .putObject("options").put("installationPath", executable.toString());
        ObjectNode entrypoint = contract.putArray("entrypoints").addObject();
        entrypoint.putObject("transport").put("protocol", "http")
                .put("baseUrl", "http://127.0.0.1").put("healthCheckPath", "/health");
        entrypoint.putObject("request").put("method", "GET").put("path", "/workload");
        contract.putObject("loadModel").put("mode", "concurrency")
                .put("concurrency", 1).put("rampUpSeconds", 0).put("durationSeconds", 1);
        contract.putObject("observationTargets").put("probeBaseUrl", "http://127.0.0.1")
                .putArray("requiredLineHits").add("example.Target#run:1");
        contract.putObject("successCriteria").put("maxErrorRatePct", 0)
                .put("minThroughputPerSec", 0.1).put("p95LatencyMs", 100);
        return input;
    }

    private static ObjectNode securityInput() {
        ObjectNode input = JSON.createObjectNode();
        input.putObject("metadata");
        ObjectNode contract = input.putObject("contract");
        contract.put("suiteType", "security").put("securityMode", "blackbox");
        ObjectNode boundary = contract.putObject("targetBoundary");
        boundary.put("environment", "local-ci").put("baseUrl", "http://127.0.0.1:8080");
        boundary.putArray("allowedHosts").add("127.0.0.1");
        boundary.putArray("allowedPorts").add(8080);
        boundary.put("externalNetworkAccess", "forbidden");
        ObjectNode entrypoint = contract.putArray("entrypoints").addObject().put("id", "transport");
        entrypoint.putObject("transport").put("type", "http").put("method", "GET").put("path", "/");
        entrypoint.putObject("baseline");
        contract.putArray("authenticationProfiles").addObject()
                .put("id", "anonymous").put("kind", "anonymous");
        contract.putArray("customCases");
        contract.putObject("exhaustiveness").put("mode", "finite_matrix")
                .put("requireAllCases", true).put("onIncomplete", "blocked");
        contract.putObject("safetyPolicy").put("maxConcurrency", 1)
                .put("maxRequestsPerSecond", 1).put("maxDurationMs", 1_000)
                .put("destructivePayloads", "forbidden").put("stateMutation", "test-tenant-only")
                .put("cleanupRequired", true);
        ObjectNode verdict = contract.putObject("verdictPolicy");
        verdict.putArray("failOnSeverity").add("critical");
        verdict.put("requireExhaustiveCompletion", true).put("blockedCountsAs", "fail");
        return input;
    }

    private static OperationDirectory directory(OperationRegistration<?, ?> registration) {
        OperationId id = registration.descriptor().operationId();
        var builtIn = OperationManifestLoader.loadBuiltIn();
        return new OperationDirectory(List.of(registration), new OperationManifestDocument(
                builtIn.version(), Map.of(id, builtIn.documentation(id))), JSON);
    }

    private void assertContinuedOwnerEvidence(
            String id, Path evidence, OwnerProbe probe) throws Exception {
        assertThat(probe.resultPersisted.await(3, TimeUnit.SECONDS)).as(id).isTrue();
        assertThat(probe.interrupted).as(id).isFalse();
        assertThat(probe.active).as(id).isFalse();
        assertThat(evidence).as(id).isRegularFile();
        var ownerResult = JSON.readTree(Files.readString(evidence));
        assertThat(ownerResult.path("status").asText())
                .as("%s: %s", id, ownerResult)
                .isEqualTo("regression_suite.execute_plan".equals(id) ? "ready" : "completed");
        assertThat(ownerResult.path("reasonCode").asText()).as(id).isEqualTo("ok");
        if ("performance_suite.execute_plan".equals(id)) {
            Path runDirectory = workspace.resolve(".mcpjvm/demo/plans/performance/performance-smoke/runs/direct-performance-1");
            assertThat(runDirectory.resolve("workload.jmeter.jmx")).isRegularFile();
            assertThat(runDirectory.resolve("workload.jmeter.jtl")).isRegularFile();
            assertThat(runDirectory.resolve("workload.jmeter.log")).isRegularFile();
        }
    }

    @SuppressWarnings("unchecked")
    private static <I, O> OperationRegistration<I, O> observe(
            OperationRegistration<I, O> registration, Path evidence, OwnerProbe probe) {
        BoundedOperationResultEncoder<O> original =
                (BoundedOperationResultEncoder<O>) registration.encoder();
        BoundedOperationResultEncoder<O> observed = new BoundedOperationResultEncoder<>() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode encode(O result) {
                var encoded = original.encode(result);
                persist(encoded, evidence, probe);
                return encoded;
            }

            @Override
            public void write(O result, OutputStream output) throws IOException {
                persist(original.encode(result), evidence, probe);
                original.write(result, output);
            }
        };
        return new OperationRegistration<>(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                registration.contract(), registration.decoder(), registration.executor(), observed,
                registration.operationCatalog(), registration.provenance());
    }

    private static void persist(
            com.fasterxml.jackson.databind.JsonNode result, Path evidence, OwnerProbe probe) {
        if (!probe.persisted.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(evidence.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), result);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            probe.resultPersisted.countDown();
        }
    }

    private static Map<String, OperationRegistration<?, ?>> byId(
            List<OperationRegistration<?, ?>> registrations) {
        return registrations.stream().collect(java.util.stream.Collectors.toMap(
                registration -> registration.descriptor().operationId().value(), registration -> registration));
    }

    private static final class OwnerProbe {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch resultPersisted = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean();
        private final AtomicBoolean persisted = new AtomicBoolean();
        private final AtomicInteger ownerCalls = new AtomicInteger();

        private ExecuteTransportResult executeTransport() {
            ownerCalls.incrementAndGet();
            active.set(true);
            started.countDown();
            try {
                release.await();
                return successfulTransport();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return ExecuteTransportResult.blockedRuntime(
                        "transport_interrupted", "unexpected worker interruption", "http", 1);
            } finally {
                active.set(false);
            }
        }

        private int executeWorkload(Path jtl, Path log) throws IOException {
            ownerCalls.incrementAndGet();
            active.set(true);
            started.countDown();
            try {
                release.await();
                Files.writeString(jtl, "elapsed,success\n10,true\n");
                Files.writeString(log, "fixture JMeter completed");
                return 0;
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return 1;
            } finally {
                active.set(false);
            }
        }
    }
}
