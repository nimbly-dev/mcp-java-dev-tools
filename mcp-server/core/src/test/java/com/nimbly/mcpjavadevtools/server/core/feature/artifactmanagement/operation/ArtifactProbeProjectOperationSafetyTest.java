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
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Behavioral safety evidence for MCPJVM-617's migrated Artifact operations. */
class ArtifactProbeProjectOperationSafetyTest {

    private static final Set<ArtifactManagementAction> OWNED = Set.of(
            ArtifactManagementAction.PROBE_CONFIG_READ,
            ArtifactManagementAction.PROBE_CONFIG_VALIDATE,
            ArtifactManagementAction.PROBE_CONFIG_UPSERT,
            ArtifactManagementAction.PROBE_CONFIG_RELOAD,
            ArtifactManagementAction.PROJECT_CONTEXT_READ,
            ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE,
            ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
            ArtifactManagementAction.PROJECT_CONTEXT_LIST);

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("readOnlyActions")
    void readOnlyRowsReturnTimeoutWithoutClaimingCooperativeStop(ArtifactManagementAction action)
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        ArtifactWorkspaceProvider provider = blockingProvider(started, release, returned);
        OperationDirectory bounded = directory(provider, new AtomicInteger(),
                registration -> withSafety(registration, 100, registration.safety().maxOutputBytes()));

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input(action), false));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        release.countDown();
        assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void callerInterruptionDoesNotCancelNonCancellableProjectList() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        OperationDirectory blocking = directory(
                blockingProvider(started, release, returned),
                new AtomicInteger(), value -> value);
        AtomicReference<OperationExecutionResult> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(blocking.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.PROJECT_CONTEXT_LIST)),
                mapper.createObjectNode(), false))), "mcpjvm-617-interrupted-caller");

        caller.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(result.get().reasonCode()).isEqualTo("operation_caller_interrupted");
        release.countDown();
        assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void mutatingRowsContinueDeterministicallyAfterTimeout() throws Exception {
        assertMutationContinues(ArtifactManagementAction.PROBE_CONFIG_UPSERT,
                mapper.createObjectNode().set("payload", validProbe()),
                workspace.resolve("probe-upsert"), ".mcpjvm/probe-config.json", 1);

        Path reloadRoot = workspace.resolve("probe-reload");
        Files.createDirectories(reloadRoot.resolve(".mcpjvm"));
        mapper.writeValue(reloadRoot.resolve(".mcpjvm/probe-config.json").toFile(), validProbe());
        assertMutationContinues(ArtifactManagementAction.PROBE_CONFIG_RELOAD,
                mapper.createObjectNode(), reloadRoot, null, 1);

        ObjectNode projectInput = mapper.createObjectNode().put("projectName", "sample");
        projectInput.set("payload", validProject(workspace.resolve("project-upsert")));
        assertMutationContinues(ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
                projectInput, workspace.resolve("project-upsert"), ".mcpjvm/sample/projects.json", 0);
    }

    @Test
    void invalidPersistedArtifactsFailClosedThroughOwnedRows() throws Exception {
        Files.createDirectories(workspace.resolve(".mcpjvm/sample"));
        Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), "{invalid");
        Files.writeString(workspace.resolve(".mcpjvm/sample/projects.json"), "{}\n");
        OperationDirectory normal = directory(() -> Optional.of(workspace), new AtomicInteger(), value -> value);

        for (ArtifactManagementAction action : List.of(
                ArtifactManagementAction.PROBE_CONFIG_READ,
                ArtifactManagementAction.PROBE_CONFIG_VALIDATE,
                ArtifactManagementAction.PROBE_CONFIG_RELOAD)) {
            assertCapabilityFailure(normal, action, mapper.createObjectNode(), "artifact_json_invalid");
        }
        for (ArtifactManagementAction action : List.of(
                ArtifactManagementAction.PROJECT_CONTEXT_READ,
                ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE)) {
            assertCapabilityFailure(normal, action,
                    mapper.createObjectNode().put("projectName", "sample"), "project_artifact_invalid");
        }
    }

    @Test
    void probeReadRejectsPersistedJsonNullWithoutAnActiveSummary() throws Exception {
        Files.createDirectories(workspace.resolve(".mcpjvm"));
        Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), "null\n");
        OperationDirectory normal = directory(() -> Optional.of(workspace), new AtomicInteger(), value -> value);

        OperationExecutionResult result = normal.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.PROBE_CONFIG_READ)),
                mapper.createObjectNode(), false));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("status").asText()).isEqualTo("probe_config_invalid");
        assertThat(result.result().path("reasonCode").asText()).isEqualTo("probe_config_invalid");
    }

    @ParameterizedTest(name = "absent {0}")
    @MethodSource("probeActions")
    void probeRowsHaveExplicitAbsentArtifactBehavior(ArtifactManagementAction action) {
        AtomicInteger reloads = new AtomicInteger();
        OperationDirectory normal = directory(() -> Optional.of(workspace), reloads, value -> value);
        JsonNode input = action == ArtifactManagementAction.PROBE_CONFIG_UPSERT
                ? mapper.createObjectNode().set("payload", validProbe())
                : mapper.createObjectNode();

        OperationExecutionResult result = normal.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input, mutating(action)));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        if (action == ArtifactManagementAction.PROBE_CONFIG_UPSERT) {
            assertThat(result.result().path("status").asText()).isEqualTo("ok");
            assertThat(Files.isRegularFile(workspace.resolve(".mcpjvm/probe-config.json"))).isTrue();
            assertThat(reloads).hasValue(1);
        } else {
            assertThat(result.result().path("status").asText()).isEqualTo("not_configured");
            assertThat(result.result().path("reasonCode").asText())
                    .isEqualTo("probe_registry_not_configured");
            assertThat(reloads).hasValue(0);
        }
    }

    @ParameterizedTest(name = "symlink escape {0}")
    @MethodSource("probeActions")
    void everyProbeRowRejectsSymlinkEscape(ArtifactManagementAction action) throws Exception {
        assumeDirectoryLink(workspace.resolve(".mcpjvm"), outside);
        OperationDirectory normal = directory(() -> Optional.of(workspace), new AtomicInteger(), value -> value);
        JsonNode input = action == ArtifactManagementAction.PROBE_CONFIG_UPSERT
                ? mapper.createObjectNode().set("payload", validProbe())
                : mapper.createObjectNode();

        assertCapabilityFailure(normal, action, input, "artifact_path_symlink_escape");
        assertThat(Files.exists(outside.resolve("probe-config.json"))).isFalse();
    }

    @Test
    void traversalAndSymlinkEscapesFailClosedWithoutExternalWrites() throws Exception {
        OperationDirectory normal = directory(() -> Optional.of(workspace), new AtomicInteger(), value -> value);
        ObjectNode traversal = mapper.createObjectNode().put("projectName", "../outside");
        traversal.set("payload", validProject(workspace));
        assertCapabilityFailure(normal, ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
                traversal, "artifact_path_segment_invalid");

    }

    @Test
    void projectListFailsClosedAtItsOutputBound() throws Exception {
        for (String name : List.of("alpha", "sample", "zeta")) {
            Path artifact = workspace.resolve(".mcpjvm").resolve(name).resolve("projects.json");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "{}\n");
        }
        OperationDirectory bounded = directory(() -> Optional.of(workspace), new AtomicInteger(),
                registration -> registration.descriptor().operationId().value()
                        .equals(operationId(ArtifactManagementAction.PROJECT_CONTEXT_LIST))
                        ? withSafety(registration, registration.safety().timeoutMillis(), 32)
                        : registration);

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.PROJECT_CONTEXT_LIST)),
                mapper.createObjectNode(), false));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_output_too_large");
        assertThat(result.result().isNull()).isTrue();
    }

    @Test
    void probeSecretsAreRedactedInPersistedAndReturnedArtifacts() throws Exception {
        OperationDirectory normal = directory(() -> Optional.of(workspace), new AtomicInteger(), value -> value);
        ObjectNode payload = validProbe().put("authorization", "Bearer mcpjvm-617-secret");
        OperationExecutionResult upsert = normal.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.PROBE_CONFIG_UPSERT)),
                mapper.createObjectNode().set("payload", payload), true));
        OperationExecutionResult read = normal.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.PROBE_CONFIG_READ)),
                mapper.createObjectNode(), false));

        assertThat(upsert.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(read.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(upsert.result().toString()).doesNotContain("mcpjvm-617-secret");
        assertThat(read.result().toString()).doesNotContain("mcpjvm-617-secret");
        assertThat(read.result().path("details").path("artifact").path("authorization").asText())
                .isEqualTo("***REDACTED***");
        assertThat(mapper.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile())
                .path("authorization").asText()).isEqualTo("[REDACTED]");
    }

    static Stream<ArtifactManagementAction> readOnlyActions() {
        return Stream.of(
                ArtifactManagementAction.PROBE_CONFIG_READ,
                ArtifactManagementAction.PROBE_CONFIG_VALIDATE,
                ArtifactManagementAction.PROJECT_CONTEXT_READ,
                ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE,
                ArtifactManagementAction.PROJECT_CONTEXT_LIST);
    }

    static Stream<ArtifactManagementAction> probeActions() {
        return Stream.of(
                ArtifactManagementAction.PROBE_CONFIG_READ,
                ArtifactManagementAction.PROBE_CONFIG_VALIDATE,
                ArtifactManagementAction.PROBE_CONFIG_UPSERT,
                ArtifactManagementAction.PROBE_CONFIG_RELOAD);
    }

    private void assertMutationContinues(
            ArtifactManagementAction action,
            JsonNode input,
            Path root,
            String expectedRelativePath,
            int expectedReloads) throws Exception {
        Files.createDirectories(root);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reloads = new AtomicInteger();
        ArtifactWorkspaceProvider provider = () -> {
            started.countDown();
            await(release);
            return Optional.of(root);
        };
        OperationDirectory bounded = directory(provider, reloads,
                registration -> withSafety(registration, 100, registration.safety().maxOutputBytes()));

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input, true));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        release.countDown();

        if (expectedRelativePath != null) {
            awaitCondition(() -> Files.isRegularFile(root.resolve(expectedRelativePath)));
            assertThat(mapper.readTree(root.resolve(expectedRelativePath).toFile()))
                    .isEqualTo(input.path("payload"));
        }
        awaitCondition(() -> reloads.get() == expectedReloads);
        assertThat(reloads).hasValue(expectedReloads);
    }

    private ArtifactWorkspaceProvider blockingProvider(
            CountDownLatch started,
            CountDownLatch release,
            CountDownLatch returned) {
        return () -> {
            started.countDown();
            await(release);
            returned.countDown();
            return Optional.of(workspace);
        };
    }

    private OperationDirectory directory(
            ArtifactWorkspaceProvider provider,
            AtomicInteger reloads,
            UnaryOperator<OperationRegistration<?, ?>> modifier) {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                provider, new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support, () -> {
            reloads.incrementAndGet();
            return new ProbeRegistry(List.of(
                    new ProbeRegistration("local", "http://127.0.0.1:9191")));
        });
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure(ArtifactOperationCatalog.TOOL_NAME, "mcpjvm-617-safety",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        List<OperationRegistration<?, ?>> registrations = ArtifactOperationRegistrations.create(catalog, mapper)
                .stream().filter(this::owned).map(modifier).toList();
        return new OperationDirectory(registrations, ownedDocument(registrations), mapper);
    }

    private OperationManifestDocument ownedDocument(List<OperationRegistration<?, ?>> registrations) {
        Set<OperationId> ids = registrations.stream()
                .map(value -> value.descriptor().operationId()).collect(java.util.stream.Collectors.toSet());
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
        return OWNED.stream().map(ArtifactProbeProjectOperationSafetyTest::operationId)
                .anyMatch(registration.descriptor().operationId().value()::equals);
    }

    private void assertCapabilityFailure(
            OperationDirectory target,
            ArtifactManagementAction action,
            JsonNode input,
            String reasonCode) {
        OperationExecutionResult result = target.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input, true));
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("status").asText()).isEqualTo(reasonCode);
        assertThat(result.result().path("reasonCode").asText()).isEqualTo(reasonCode);
        assertThat(result.result().path("reasonMeta").path("artifactType").asText())
                .isEqualTo(action.artifactType().value());
        assertThat(result.result().path("reasonMeta").path("action").asText())
                .isEqualTo(action.action().value());
    }

    private JsonNode input(ArtifactManagementAction action) {
        return switch (action) {
            case PROJECT_CONTEXT_READ, PROJECT_CONTEXT_VALIDATE ->
                    mapper.createObjectNode().put("projectName", "sample");
            default -> mapper.createObjectNode();
        };
    }

    private ObjectNode validProbe() {
        ObjectNode probe = mapper.createObjectNode();
        probe.put("defaultProfile", "local");
        probe.putObject("profiles").putObject("local").putObject("probes")
                .putObject("local").put("baseUrl", "http://127.0.0.1:9191");
        return probe;
    }

    private ObjectNode validProject(Path root) {
        ObjectNode project = mapper.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", root.toString()).set("defaults", mapper.createObjectNode()
                        .set("orchestrator", mapper.createObjectNode()
                                .put("resumePollMax", 1)
                                .put("resumePollIntervalMs", 10)
                                .put("resumePollTimeoutMs", 100)));
        return project;
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return action == ArtifactManagementAction.PROBE_CONFIG_UPSERT
                || action == ArtifactManagementAction.PROBE_CONFIG_RELOAD;
    }

    private static OperationRegistration<?, ?> withSafety(
            OperationRegistration<?, ?> registration, long timeoutMillis, int maxOutputBytes) {
        OperationSafetyPolicy original = registration.safety();
        OperationSafetyPolicy replacement = new OperationSafetyPolicy(
                original.sideEffect(), original.confirmationRequired(), original.credentialPolicy(),
                original.redactionPolicy(), timeoutMillis, original.cancellationSupported(),
                original.maxInputBytes(), maxOutputBytes);
        return rebind(registration, replacement);
    }

    private static <I, O> OperationRegistration<I, O> rebind(
            OperationRegistration<I, O> registration, OperationSafetyPolicy safety) {
        return new OperationRegistration<>(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), safety),
                registration.decoder(), registration.executor(), registration.encoder(),
                registration.operationCatalog(), registration.provenance());
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.artifactType().value() + "." + action.action().value();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("unexpected mutation worker interruption", exception);
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void assumeDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows directory junctions provide the same reparse-point containment proof.
        }
        Files.deleteIfExists(link);
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Assumptions.assumeTrue(false, "directory links are unavailable in this environment");
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            if (process.waitFor() != 0) {
                Assumptions.assumeTrue(false, "directory junctions are unavailable in this environment");
            }
        } finally {
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
            process.destroy();
        }
    }
}
