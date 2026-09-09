package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Behavioral timeout and caller-interruption evidence for MCPJVM-618 plan rows. */
class ArtifactPlanOperationSafetyTest {

    private static final Set<ArtifactManagementAction> OWNED = Set.of(
            ArtifactManagementAction.PERFORMANCE_PLAN_READ,
            ArtifactManagementAction.PERFORMANCE_PLAN_VALIDATE,
            ArtifactManagementAction.PERFORMANCE_PLAN_UPSERT,
            ArtifactManagementAction.PERFORMANCE_PLAN_LIST,
            ArtifactManagementAction.REGRESSION_PLAN_READ,
            ArtifactManagementAction.REGRESSION_PLAN_VALIDATE,
            ArtifactManagementAction.REGRESSION_PLAN_UPSERT,
            ArtifactManagementAction.REGRESSION_PLAN_LIST,
            ArtifactManagementAction.SECURITY_PLAN_READ,
            ArtifactManagementAction.SECURITY_PLAN_VALIDATE,
            ArtifactManagementAction.SECURITY_PLAN_UPSERT,
            ArtifactManagementAction.SECURITY_PLAN_LIST);

    @TempDir
    Path workspace;

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "deadline {0}")
    @MethodSource("readOnlyActions")
    void readOnlyRowsReturnTimeoutAndContinueToCompletion(ArtifactManagementAction action)
            throws Exception {
        seedPlan(workspace, suite(action), "sample");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        OperationDirectory bounded = directory(blockingProvider(workspace, started, release),
                registration -> observe(withTimeout(registration, 100), completed));

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input(action), false));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        release.countDown();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest(name = "caller interruption {0}")
    @MethodSource("ownedActions")
    void callerInterruptionDoesNotStopAnyPlanRow(ArtifactManagementAction action)
            throws Exception {
        seedPlan(workspace, suite(action), "sample");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        OperationDirectory blocking = directory(blockingProvider(workspace, started, release),
                registration -> observe(registration, completed));
        AtomicReference<OperationExecutionResult> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(blocking.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input(action), mutating(action)))),
                "mcpjvm-618-interrupted-caller");

        caller.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(result.get().reasonCode()).isEqualTo("operation_caller_interrupted");
        release.countDown();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        if (mutating(action)) {
            assertPersisted(workspace, suite(action), "sample", payload(suite(action)));
        }
    }

    @ParameterizedTest(name = "continued mutation {0}")
    @MethodSource("upsertActions")
    void upsertsContinueDeterministicallyAndPersistExactStateAfterTimeout(
            ArtifactManagementAction action) throws Exception {
        Path root = workspace.resolve(suite(action) + "-timeout");
        Files.createDirectories(root);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        OperationDirectory bounded = directory(blockingProvider(root, started, release),
                registration -> observe(withTimeout(registration, 100), completed));

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input(action), true));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        release.countDown();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertPersisted(root, suite(action), "sample", payload(suite(action)));
    }

    static Stream<ArtifactManagementAction> ownedActions() {
        return OWNED.stream();
    }

    static Stream<ArtifactManagementAction> readOnlyActions() {
        return OWNED.stream().filter(action -> !mutating(action));
    }

    static Stream<ArtifactManagementAction> upsertActions() {
        return OWNED.stream().filter(ArtifactPlanOperationSafetyTest::mutating);
    }

    private OperationDirectory directory(
            ArtifactWorkspaceProvider provider,
            UnaryOperator<OperationRegistration<?, ?>> modifier) {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                provider, new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support,
                () -> new com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry(List.of()));
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure(ArtifactOperationCatalog.TOOL_NAME, "mcpjvm-618-safety",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        List<OperationRegistration<?, ?>> registrations = ArtifactOperationRegistrations.create(catalog, mapper)
                .stream().filter(this::owned).map(modifier).toList();
        return new OperationDirectory(registrations, ownedDocument(registrations), mapper);
    }

    private OperationManifestDocument ownedDocument(List<OperationRegistration<?, ?>> registrations) {
        Set<OperationId> ids = registrations.stream().map(value -> value.descriptor().operationId())
                .collect(java.util.stream.Collectors.toSet());
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        Map<OperationId, com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation>
                documentation = new LinkedHashMap<>();
        all.operations().forEach((id, value) -> {
            if (ids.contains(id)) {
                documentation.put(id, value);
            }
        });
        return new OperationManifestDocument(all.version(), documentation);
    }

    private boolean owned(OperationRegistration<?, ?> registration) {
        return OWNED.stream().map(ArtifactPlanOperationSafetyTest::operationId)
                .anyMatch(registration.descriptor().operationId().value()::equals);
    }

    private ArtifactWorkspaceProvider blockingProvider(
            Path root, CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            await(release);
            return Optional.of(root);
        };
    }

    private JsonNode input(ArtifactManagementAction action) {
        ObjectNode input = mapper.createObjectNode().put("projectName", "demo");
        if (action.action().value().equals("list")) {
            return input;
        }
        input.put("planName", "sample");
        if (mutating(action)) {
            input.set("payload", payload(suite(action)));
        }
        return input;
    }

    private ObjectNode payload(String suite) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode metadata = payload.putObject("metadata");
        ObjectNode contract = payload.putObject("contract");
        if ("performance".equals(suite)) {
            metadata.put("suiteType", "performance").putObject("execution")
                    .put("intent", "performance");
            contract.put("suiteType", "performance");
            contract.putObject("loadModel").put("mode", "concurrency")
                    .put("concurrency", 1).put("durationSeconds", 1);
            contract.putObject("successCriteria");
            contract.putObject("workloadProvider").put("type", "jmeter");
            contract.putArray("entrypoints").addObject().put("id", "health");
        } else if ("regression".equals(suite)) {
            contract.putArray("steps").addObject().put("id", "health").put("protocol", "http");
        } else {
            contract.put("suiteType", "security");
        }
        payload.put("plan", "# " + suite + " plan\n");
        return payload;
    }

    private void seedPlan(Path root, String suite, String planName) throws Exception {
        ObjectNode expected = payload(suite);
        Path plan = root.resolve(".mcpjvm/demo/plans").resolve(suite).resolve(planName);
        Files.createDirectories(plan);
        ArtifactJsonStore store = new ArtifactJsonStore(mapper);
        store.write(plan.resolve("metadata.json"), expected.path("metadata"));
        store.write(plan.resolve("contract.json"), expected.path("contract"));
        Files.writeString(plan.resolve("plan.md"), expected.path("plan").asText());
    }

    private void assertPersisted(Path root, String suite, String planName, JsonNode expected)
            throws Exception {
        Path plan = root.resolve(".mcpjvm/demo/plans").resolve(suite).resolve(planName);
        String metadata = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(expected.path("metadata")) + "\n";
        String contract = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(expected.path("contract")) + "\n";
        assertThat(Files.readString(plan.resolve("metadata.json"))).isEqualTo(metadata);
        assertThat(Files.readString(plan.resolve("contract.json"))).isEqualTo(contract);
        assertThat(Files.readString(plan.resolve("plan.md"))).isEqualTo(expected.path("plan").asText());
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return "upsert".equals(action.action().value());
    }

    private static String suite(ArtifactManagementAction action) {
        return action.artifactType().value().replace("_plan", "");
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.artifactType().value() + "." + action.action().value();
    }

    private static <I, O> OperationRegistration<I, O> withTimeout(
            OperationRegistration<I, O> registration, long timeoutMillis) {
        OperationSafetyPolicy original = registration.safety();
        return rebind(registration, new OperationSafetyPolicy(
                original.sideEffect(), original.confirmationRequired(), original.credentialPolicy(),
                original.redactionPolicy(), timeoutMillis, original.cancellationSupported(),
                original.maxInputBytes(), original.maxOutputBytes()), registration.executor());
    }

    @SuppressWarnings("unchecked")
    private static <I, O> OperationRegistration<I, O> observe(
            OperationRegistration<I, O> registration, CountDownLatch completed) {
        BoundedOperationResultEncoder<O> original =
                (BoundedOperationResultEncoder<O>) registration.encoder();
        BoundedOperationResultEncoder<O> observed = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(O result) {
                try {
                    return original.encode(result);
                } finally {
                    completed.countDown();
                }
            }

            @Override
            public void write(O result, OutputStream output) throws IOException {
                try {
                    original.write(result, output);
                } finally {
                    completed.countDown();
                }
            }
        };
        return new OperationRegistration<>(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                registration.contract(), registration.decoder(), registration.executor(), observed,
                registration.operationCatalog(), registration.legacyIdentity());
    }

    private static <I, O> OperationRegistration<I, O> rebind(
            OperationRegistration<I, O> registration,
            OperationSafetyPolicy safety,
            OperationExecutor<I, O> executor) {
        return new OperationRegistration<>(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), safety),
                registration.decoder(), executor, registration.encoder(),
                registration.operationCatalog(), registration.legacyIdentity());
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
