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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Timeout, interruption, and deterministic-continuation evidence for MCPJVM-619. */
class ArtifactRunExportOperationSafetyTest {

    private static final Set<ArtifactManagementAction> OWNED = Set.of(
            ArtifactManagementAction.RUN_RESULT_READ,
            ArtifactManagementAction.RUN_RESULT_UPSERT,
            ArtifactManagementAction.RUN_RESULT_LIST,
            ArtifactManagementAction.RUN_RESULT_REBUILD,
            ArtifactManagementAction.RUN_RESULT_BACKFILL,
            ArtifactManagementAction.RUN_RESULT_CUTOVER,
            ArtifactManagementAction.RUN_RESULT_QUERY,
            ArtifactManagementAction.RUN_RESULT_CLEANUP,
            ArtifactManagementAction.EXECUTION_EXPORT_READ,
            ArtifactManagementAction.EXECUTION_EXPORT_LIST,
            ArtifactManagementAction.EXECUTION_EXPORT_GENERATE);

    @TempDir
    Path workspace;

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "deadline {0}")
    @MethodSource("ownedActions")
    void deadlineReturnsTimeoutThenOperationContinuesDeterministically(
            ArtifactManagementAction action) throws Exception {
        seedArtifacts(workspace);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicReference<JsonNode> finalResult = new AtomicReference<>();
        OperationDirectory directory = directory(blockingProvider(started, release),
                completed, finalResult, 100);

        OperationExecutionResult result = directory.execute(invocation(action));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        release.countDown();
        assertContinuation(action, completed, finalResult);
    }

    @ParameterizedTest(name = "caller interruption {0}")
    @MethodSource("ownedActions")
    void callerInterruptionReturnsCancelledThenOperationContinuesDeterministically(
            ArtifactManagementAction action) throws Exception {
        seedArtifacts(workspace);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicReference<JsonNode> finalResult = new AtomicReference<>();
        OperationDirectory directory = directory(blockingProvider(started, release),
                completed, finalResult, 60_000);
        AtomicReference<OperationExecutionResult> callerResult = new AtomicReference<>();
        Thread caller = new Thread(() -> callerResult.set(directory.execute(invocation(action))),
                "mcpjvm-619-interrupted-caller");

        caller.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(callerResult.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(callerResult.get().reasonCode()).isEqualTo("operation_caller_interrupted");
        release.countDown();
        assertContinuation(action, completed, finalResult);
    }

    static Stream<ArtifactManagementAction> ownedActions() {
        return OWNED.stream();
    }

    private void assertContinuation(
            ArtifactManagementAction action,
            CountDownLatch completed,
            AtomicReference<JsonNode> finalResult) throws Exception {
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(finalResult.get()).as(action.routeId()).isNotNull();
        assertThat(finalResult.get().path("reasonCode").asText()).isEqualTo("success");
        if (action == ArtifactManagementAction.RUN_RESULT_UPSERT) {
            assertThat(mapper.readTree(workspace.resolve(
                    ".mcpjvm/demo/plans/regression/health/runs/run-1/execution.result.json").toFile())
                    .path("status").asText()).isEqualTo("updated");
        }
        if (action == ArtifactManagementAction.RUN_RESULT_REBUILD) {
            assertThat(new SqliteRunStateStore(mapper).query(
                    workspace.resolve(".mcpjvm/demo/run-state.sqlite"), "demo", "run_state")
                    .get("items").toString()).contains("run-1", "run-2");
        }
        if (action == ArtifactManagementAction.RUN_RESULT_BACKFILL) {
            assertThat(finalResult.get().path("details").path("backfill").path("imported").asInt())
                    .isEqualTo(1);
        }
        if (action == ArtifactManagementAction.RUN_RESULT_CUTOVER) {
            assertThat(Files.readString(workspace.resolve(
                    ".mcpjvm/demo/state-store.cutover.json"))).contains("cutover_complete");
        }
        if (action == ArtifactManagementAction.RUN_RESULT_CLEANUP) {
            assertThat(finalResult.get().path("details").path("cleanup").path("deletedRuns").asInt())
                    .isEqualTo(1);
            assertThat(new SqliteRunStateStore(mapper).query(
                    workspace.resolve(".mcpjvm/demo/run-state.sqlite"), "demo", "run_state")
                    .get("items").toString()).contains("run-2").doesNotContain("run-1");
        }
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_GENERATE) {
            assertThat(Files.isDirectory(workspace.resolve(".mcpjvm/demo/exports"))).isTrue();
        }
        awaitOperationWorkersQuiescent();
    }

    private OperationDirectory directory(
            ArtifactWorkspaceProvider provider,
            CountDownLatch completed,
            AtomicReference<JsonNode> finalResult,
            long timeoutMillis) {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                provider, new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support,
                () -> new com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry(List.of()));
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure("artifact_management", "mcpjvm-619-safety",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        List<OperationRegistration<?, ?>> registrations = new ArrayList<>();
        for (OperationRegistration<?, ?> value : ArtifactOperationRegistrations.create(catalog, mapper)) {
            if (owned(value)) {
                registrations.add(observeUnchecked(value, completed, finalResult, timeoutMillis));
            }
        }
        return new OperationDirectory(registrations, ownedDocument(registrations), mapper);
    }

    private ArtifactWorkspaceProvider blockingProvider(
            CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            awaitUninterruptibly(release);
            return Optional.of(workspace);
        };
    }

    private OperationInvocation invocation(ArtifactManagementAction action) {
        return new OperationInvocation(OperationId.of(operationId(action)), input(action), mutating(action));
    }

    private JsonNode input(ArtifactManagementAction action) {
        if (action.artifactType().value().equals("execution_export")) {
            return exportInput(action);
        }
        if (action == ArtifactManagementAction.RUN_RESULT_UPSERT) {
            return runInput().set("payload", mapper.createObjectNode().put("status", "updated"));
        }
        if (action == ArtifactManagementAction.RUN_RESULT_BACKFILL) {
            return mapper.createObjectNode().put("projectName", "demo")
                    .put("stateSurface", "correlation_state");
        }
        if (Set.of(ArtifactManagementAction.RUN_RESULT_REBUILD,
                ArtifactManagementAction.RUN_RESULT_CUTOVER,
                ArtifactManagementAction.RUN_RESULT_QUERY).contains(action)) {
            return mapper.createObjectNode().put("projectName", "demo");
        }
        if (action == ArtifactManagementAction.RUN_RESULT_CLEANUP) {
            ObjectNode cleanup = mapper.createObjectNode().put("projectName", "demo");
            cleanup.putObject("retention").put("dryRun", false)
                    .put("terminalOlderThanDays", 1)
                    .put("keepMostRecentTerminalRuns", 1)
                    .put("maxDeleteBatch", 1);
            return cleanup;
        }
        return runInput();
    }

    private JsonNode exportInput(ArtifactManagementAction action) {
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_GENERATE) {
            return mapper.createObjectNode().put("projectName", "demo").put("mode", "sh")
                    .put("planName", "health").put("executionProfile", "smoke");
        }
        ObjectNode input = mapper.createObjectNode().put("projectName", "demo");
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_READ) {
            input.putObject("query").put("exportId", "seed-export");
        }
        return input;
    }

    private ObjectNode runInput() {
        return mapper.createObjectNode().put("projectName", "demo")
                .put("suiteType", "regression").put("planName", "health")
                .put("runId", "run-1");
    }

    private void seedArtifacts(Path root) throws Exception {
        Path project = root.resolve(".mcpjvm/demo");
        Files.createDirectories(project.resolve("plans/regression/health/runs/run-1"));
        ObjectNode projects = mapper.createObjectNode();
        ObjectNode selected = projects.putArray("workspaces").addObject()
                .put("projectRoot", root.toString());
        selected.putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke").put("suiteType", "regression")
                .put("executionPolicy", "stop_on_fail").putArray("plans").addObject()
                .put("order", 1).put("planName", "health").put("onFail", "inherit");
        writeJson(project.resolve("projects.json"), projects);
        writeJson(project.resolve("plans/regression/health/metadata.json"), mapper.createObjectNode());
        ObjectNode contract = mapper.createObjectNode();
        contract.putArray("targets").addObject();
        contract.putArray("steps").addObject().put("id", "health").put("protocol", "http")
                .putObject("transport").putObject("http").put("method", "GET")
                .put("url", "http://127.0.0.1:9196/health");
        writeJson(project.resolve("plans/regression/health/contract.json"), contract);
        writeJson(project.resolve("plans/regression/health/runs/run-1/execution.result.json"),
                mapper.createObjectNode().put("status", "pass")
                        .put("startedAt", 1).put("endedAt", 1));
        Path secondRun = project.resolve("plans/regression/health/runs/run-2");
        Files.createDirectories(secondRun);
        writeJson(secondRun.resolve("execution.result.json"),
                mapper.createObjectNode().put("status", "pass")
                        .put("startedAt", 2).put("endedAt", 2));
        writeJson(project.resolve("plans/regression/health/runs/run-1/correlation.json"),
                mapper.createObjectNode().put("correlationSessionId", "legacy-run-1"));
        new SqliteRunStateStore(mapper).rebuild(project.resolve("run-state.sqlite"), "demo");
        Path export = project.resolve("exports/seed-export");
        Files.createDirectories(export);
        Files.writeString(export.resolve("manifest.json"), "{}\n");
    }

    private void writeJson(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private boolean owned(OperationRegistration<?, ?> registration) {
        return OWNED.stream().map(ArtifactRunExportOperationSafetyTest::operationId)
                .anyMatch(registration.descriptor().operationId().value()::equals);
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

    private static <I, O> OperationRegistration<I, O> observe(
            OperationRegistration<I, O> registration,
            CountDownLatch completed,
            AtomicReference<JsonNode> finalResult,
            long timeoutMillis) {
        OperationSafetyPolicy safety = registration.safety();
        OperationSafetyPolicy bounded = new OperationSafetyPolicy(
                safety.sideEffect(), safety.confirmationRequired(), safety.credentialPolicy(),
                safety.redactionPolicy(), timeoutMillis, safety.cancellationSupported(),
                safety.maxInputBytes(), safety.maxOutputBytes());
        return rebind(registration, bounded, observingEncoder(registration, completed, finalResult));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OperationRegistration<?, ?> observeUnchecked(
            OperationRegistration<?, ?> registration,
            CountDownLatch completed,
            AtomicReference<JsonNode> finalResult,
            long timeoutMillis) {
        return observe((OperationRegistration) registration, completed, finalResult, timeoutMillis);
    }

    @SuppressWarnings("unchecked")
    private static <I, O> BoundedOperationResultEncoder<O> observingEncoder(
            OperationRegistration<I, O> registration,
            CountDownLatch completed,
            AtomicReference<JsonNode> finalResult) {
        BoundedOperationResultEncoder<O> original =
                (BoundedOperationResultEncoder<O>) registration.encoder();
        return new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(O result) {
                JsonNode encoded = original.encode(result);
                finalResult.set(encoded);
                completed.countDown();
                return encoded;
            }

            @Override
            public void write(O result, OutputStream output) throws IOException {
                finalResult.set(original.encode(result));
                original.write(result, output);
                completed.countDown();
            }
        };
    }

    private static <I, O> OperationRegistration<I, O> rebind(
            OperationRegistration<I, O> registration,
            OperationSafetyPolicy safety,
            BoundedOperationResultEncoder<O> encoder) {
        return new OperationRegistration<>(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), safety),
                registration.decoder(), registration.executor(), encoder,
                registration.operationCatalog(), registration.provenance());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
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

    private static void awaitOperationWorkersQuiescent() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean active = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> "mcpjvm-operation".equals(thread.getName()))
                    .map(Thread::getState)
                    .anyMatch(state -> state == Thread.State.RUNNABLE || state == Thread.State.BLOCKED);
            if (!active) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(Thread.getAllStackTraces().keySet())
                .filteredOn(thread -> "mcpjvm-operation".equals(thread.getName()))
                .allSatisfy(thread -> assertThat(thread.getState())
                        .isNotIn(Thread.State.RUNNABLE, Thread.State.BLOCKED));
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.routeId().replace('/', '.');
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return Set.of("upsert", "rebuild", "backfill", "cutover", "cleanup", "generate")
                .contains(action.action().value());
    }
}
