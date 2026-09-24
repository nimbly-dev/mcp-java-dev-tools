package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultExecutionProfileExportFeatureTest {

    @TempDir
    Path workspace;

    @Test
    void dispatchesTheCompleteExportAllowlist() {
        ExecutionExportArtifactGateway gateway = request ->
                new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult(
                        "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                        Map.of("exportId", "export-1"));
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog(gateway));

        var result = feature.execute(new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, null, null, null, null, null,
                "sh", null, null, null, null, Map.of(), Map.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("exportId", "export-1");
    }

    @Test
    void returnsDeterministicInvalidRequestForMissingFeatureRequest() {
        ExecutionExportArtifactGateway gateway = request ->
                new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult(
                        "execution_profile_export", "ok", "success", null, null, "", Map.of(), Map.of());
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog(gateway));

        var result = feature.execute(null);

        assertThat(result.resultType()).isEqualTo("report");
        assertThat(result.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void exposesCompleteCatalogAndGeneratedTraceInventory() {
        ExecutionProfileExportOperationCatalog catalog = catalog(request -> null);

        assertThat(catalog.catalog()).hasSize(1);
        OperationDescriptor descriptor = catalog.describe(ExecutionProfileExportAction.EXPORT);
        assertThat(descriptor.toolName()).isEqualTo("execution_profile_export");
        assertThat(descriptor.action()).isEqualTo("export");
        assertThat(descriptor.requestType()).contains("ExecutionProfileExportRequest");
        assertThat(descriptor.resultType()).contains("ExecutionProfileExportResult");
        assertThat(catalog.traceInventory()).hasSize(1);
        assertThat(catalog.traceInventory().getFirst().executableOwner())
                .isEqualTo(descriptor.executableOwner());
    }

    @Test
    void registersActionlessExportDefaultsAliasesAndDeterministicContinuation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ExecutionProfileExportOperationCatalog catalog = catalog(request ->
                new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult(
                        "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                        Map.of("exportId", "registered-export", "mode", request.input().path("mode").asText())));
        var registration = ExecutionProfileExportOperationRegistrations.create(catalog, mapper).getFirst();

        assertThat(registration.descriptor().operationId().value()).isEqualTo("execution_profile_export.export");
        assertThat(registration.provenance().actionless()).isTrue();
        assertThat(registration.provenance().invocationAction()).isEmpty();
        assertThat(registration.safety().cancellationSupported()).isFalse();
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.NOT_CANCELLABLE);

        var result = registration.execute(mapper.readTree("""
                {"mode":"sh","type":"sh","includeResolvedSecrets":false,
                "contextBindings":{"auth.bearer":"AUTH_TOKEN"},
                "contextValues":{"apiBaseUrl":"http://127.0.0.1"}}
                """));
        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("details").path("mode").asText()).isEqualTo("sh");

        writeContractEvidence(registration, mapper);
    }

    @Test
    void concreteFilesystemRegistrationMatchesTypedOwner() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        seedExportArtifacts(mapper);
        ExecutionProfileExportOperationCatalog catalog = realCatalog(mapper, () -> Optional.of(workspace));
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog);
        var registration = ExecutionProfileExportOperationRegistrations.create(catalog, mapper).getFirst();
        OperationDirectory directory = directory(registration, mapper);

        var typed = feature.execute(new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, "demo", "typed-export", "regression-smoke",
                null, null, "sh", "sh", false, false, false, Map.of(), Map.of()));
        OperationExecutionResult cde = directory.execute(new OperationInvocation(
                OperationId.of("execution_profile_export.export"), mapper.readTree("""
                        {"projectName":"demo","exportId":"cde-export",
                        "executionProfile":"regression-smoke","mode":"sh","type":"sh",
                        "includeResolvedSecrets":false,"includeRuntimeStartup":false,
                        "includeHealthcheckGate":false}
                        """), true));

        assertThat(cde.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(cde.result().path("status").asText()).isEqualTo(typed.status());
        Path typedScript = outputPath(typed.details());
        Path cdeScript = Path.of(cde.result().path("details").path("output").path("scriptPathAbs").asText());
        assertThat(typedScript).isRegularFile();
        assertThat(cdeScript).isRegularFile();
        assertThat(Files.readString(cdeScript)).isEqualTo(Files.readString(typedScript))
                .contains("http://127.0.0.1:9191/export-core");
    }

    @Test
    void omittedRuntimeAndHealthFlagsPreserveTypedWorkspaceDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        seedExportArtifacts(mapper);
        ExecutionProfileExportOperationCatalog catalog = realCatalog(mapper, () -> Optional.of(workspace));
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog);
        var registration = ExecutionProfileExportOperationRegistrations.create(catalog, mapper).getFirst();

        var typed = feature.execute(new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, "demo", "typed-omitted", "regression-smoke",
                null, null, "sh", "sh", false, null, null, Map.of(), Map.of()));
        OperationExecutionResult cde = directory(registration, mapper).execute(new OperationInvocation(
                OperationId.of("execution_profile_export.export"), mapper.readTree("""
                        {"projectName":"demo","exportId":"cde-omitted",
                        "executionProfile":"regression-smoke","mode":"sh","type":"sh",
                        "includeResolvedSecrets":false}
                        """), true));

        assertThat(cde.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        String typedScript = Files.readString(outputPath(typed.details()));
        String cdeScript = Files.readString(Path.of(
                cde.result().path("details").path("output").path("scriptPathAbs").asText()));
        assertThat(cdeScript).isEqualTo(typedScript)
                .contains("includeRuntimeStartup=true includeHealthcheckGate=true");
    }

    @Test
    void concreteExportOwnerIsContainedWhenNonCancellableCapacitySaturates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        seedExportArtifacts(mapper);
        CountDownLatch started = new CountDownLatch(16);
        CountDownLatch release = new CountDownLatch(1);
        ArtifactWorkspaceProvider blockingWorkspace = () -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test export owner was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("non-cancellable export owner was interrupted", exception);
            }
            return Optional.of(workspace);
        };
        ExecutionProfileExportOperationCatalog catalog = realCatalog(mapper, blockingWorkspace);
        OperationRegistration<?, ?> registration = ExecutionProfileExportOperationRegistrations
                .create(catalog, mapper).getFirst();
        OperationDirectory bounded = directory(withTimeout(registration, 100), mapper);
        ExecutorService callers = Executors.newFixedThreadPool(16);
        List<Future<OperationExecutionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 16; index++) {
                int exportIndex = index;
                futures.add(callers.submit(() -> bounded.execute(new OperationInvocation(
                        OperationId.of("execution_profile_export.export"),
                        exportInput(mapper, "saturation-" + exportIndex), true))));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (Future<OperationExecutionResult> future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS).status())
                        .isEqualTo(OperationExecutionStatus.TIMEOUT);
            }

            OperationExecutionResult saturated = bounded.execute(new OperationInvocation(
                    OperationId.of("execution_profile_export.export"),
                    exportInput(mapper, "saturated"), true));
            assertThat(saturated.reasonCode()).isEqualTo("operation_execution_capacity");
        } finally {
            release.countDown();
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }

        OperationDirectory recovered = directory(ExecutionProfileExportOperationRegistrations
                .create(realCatalog(mapper, () -> Optional.of(workspace)), mapper).getFirst(), mapper);
        OperationExecutionResult result = awaitExportRecovery(recovered, mapper);
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(Path.of(result.result().path("details").path("output").path("scriptPathAbs").asText()))
                .isRegularFile();
    }

    private void writeContractEvidence(
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration<?, ?> registration,
            ObjectMapper mapper) throws Exception {
        OperationId id = registration.descriptor().operationId();
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        OperationDirectory directory = new OperationDirectory(
                List.of(registration),
                new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id))), mapper);
        Path evidence = Path.of("target", "mcpjvm-622-evidence");
        Files.createDirectories(evidence);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest-execution-profile-export.json").toFile(),
                directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-execution-profile-export.json").toFile(), directory.traceInventory());
    }

    private ExecutionProfileExportOperationCatalog realCatalog(
            ObjectMapper mapper, ArtifactWorkspaceProvider workspaceProvider) {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                workspaceProvider, new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        return catalog(new ExecutionExportOperations(support));
    }

    private OperationDirectory directory(OperationRegistration<?, ?> registration, ObjectMapper mapper) {
        OperationId id = registration.descriptor().operationId();
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        return new OperationDirectory(List.of(registration),
                new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id))), mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OperationRegistration<?, ?> withTimeout(
            OperationRegistration registration, long timeoutMillis) {
        OperationSafetyPolicy safety = registration.safety();
        OperationSafetyPolicy bounded = new OperationSafetyPolicy(
                safety.sideEffect(), safety.confirmationRequired(), safety.credentialPolicy(),
                safety.redactionPolicy(), timeoutMillis, safety.cancellationSupported(),
                safety.maxInputBytes(), safety.maxOutputBytes());
        return new OperationRegistration(
                registration.descriptor(), registration.requestType(), registration.resultType(),
                new OperationRegistrationContract(
                        registration.inputSchema(), registration.resultSchema(), bounded),
                registration.decoder(), registration.executor(), registration.encoder(),
                registration.operationCatalog(), registration.provenance());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode exportInput(
            ObjectMapper mapper, String exportId) {
        return mapper.createObjectNode()
                .put("projectName", "demo")
                .put("exportId", exportId)
                .put("executionProfile", "regression-smoke")
                .put("mode", "sh")
                .put("type", "sh")
                .put("includeResolvedSecrets", false);
    }

    private OperationExecutionResult awaitExportRecovery(
            OperationDirectory directory, ObjectMapper mapper) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        OperationExecutionResult result;
        do {
            result = directory.execute(new OperationInvocation(
                    OperationId.of("execution_profile_export.export"),
                    exportInput(mapper, "recovered"), true));
            if (!"operation_execution_capacity".equals(result.reasonCode())) {
                return result;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return result;
    }

    private static Path outputPath(Map<String, Object> details) {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) details.get("output");
        return Path.of(String.valueOf(output.get("scriptPathAbs")));
    }

    private void seedExportArtifacts(ObjectMapper mapper) throws Exception {
        Path projectRoot = workspace.resolve(".mcpjvm/demo");
        Path planRoot = projectRoot.resolve("plans/regression/regression-smoke");
        Files.createDirectories(planRoot);
        var project = mapper.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString())
                .putArray("executionProfiles").addObject()
                .put("executionProfile", "regression-smoke")
                .put("suiteType", "regression")
                .putArray("plans").addObject().put("order", 1).put("planName", "regression-smoke");
        Files.writeString(projectRoot.resolve("projects.json"), project.toPrettyString());
        Files.writeString(planRoot.resolve("metadata.json"),
                "{\"suiteType\":\"regression\",\"execution\":{\"intent\":\"regression\"}}");
        Files.writeString(planRoot.resolve("contract.json"), """
                {"targets":[{}],"steps":[{"order":1,"id":"transport","protocol":"http",
                "transport":{"http":{"method":"GET","url":"http://127.0.0.1:9191/export-core"}},
                "expect":[{"id":"status","actualPath":"response.status",
                "operator":"field_equals","expected":200}]}]}
                """);
    }

    private ExecutionProfileExportOperationCatalog catalog(ExecutionExportArtifactGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ExecutionProfileExportOperationCatalog(
                new ExportExecutionProfileOperation(
                        gateway,
                        new ExecutionProfileExportArtifactInputMapper(objectMapper),
                        new OperationTraceMetadata(
                                "ExecutionProfileExportMcpTool",
                                "ExecutionProfileExportMcpRequestMapper",
                                "DefaultExecutionProfileExportFeature",
                                "ExecutionProfileExportMcpResponseMapper",
                                "DefaultExecutionProfileExportFeatureTest",
                                "filesystem_artifact_export",
                                Map.of(
                                        "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                        "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class.getName()))),
                new OperationExposure(
                        "execution_profile_export", "ExecutionProfileExportMcpTool", List.of("export")));
    }
}
