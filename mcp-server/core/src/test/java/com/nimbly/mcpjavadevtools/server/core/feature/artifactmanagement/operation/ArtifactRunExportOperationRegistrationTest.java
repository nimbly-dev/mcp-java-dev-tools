package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.DefaultArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.operation.ArtifactOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestAssembler;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable operation-directory evidence for MCPJVM-619's eleven rows. */
class ArtifactRunExportOperationRegistrationTest {

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
    private Map<String, OperationRegistration<?, ?>> registrations;
    private OperationDirectory directory;
    private ArtifactManagementFeature legacyFeature;

    @BeforeEach
    void setUp() throws Exception {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> java.util.Optional.of(workspace), new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support,
                () -> new com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry(List.of()));
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure("artifact_management", "mcpjvm-619",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        legacyFeature = new DefaultArtifactManagementFeature(catalog);
        List<OperationRegistration<?, ?>> owned = ArtifactOperationRegistrations.create(catalog, mapper)
                .stream().filter(this::owned).toList();
        registrations = owned.stream().collect(java.util.stream.Collectors.toMap(
                value -> value.descriptor().operationId().value(), value -> value,
                (left, right) -> left, LinkedHashMap::new));
        OperationManifestDocument document = ownedDocument();
        OperationManifestAssembler.assembleStrict(owned, document);
        directory = new OperationDirectory(owned, document, mapper);
        seedArtifacts();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedActions")
    void registersExactOwnerIdentitySchemaAndCancellation(ArtifactManagementAction action) {
        OperationRegistration<?, ?> registration = registrations.get(operationId(action));

        assertThat(registration).isNotNull();
        Class<?> owner = action.artifactType().value().equals("run_result")
                ? RunResultOperations.class : ExecutionExportOperations.class;
        assertThat(registration.descriptor().executableOwner()).startsWith(owner.getName());
        assertThat(registration.legacyIdentity().toolName()).isEqualTo("artifact_management");
        assertThat(registration.legacyIdentity().action()).isEqualTo(action.routeId());
        assertThat(registration.legacyIdentity().discriminators())
                .containsEntry("artifactType", action.artifactType().value())
                .containsEntry("action", action.action().value());
        String scenario = scenario(action);
        assertThat(registration.legacyIdentity().normalization()).contains(scenario);
        assertThat(registration.legacyIdentity().resultComparison()).contains(scenario);
        assertThat(registration.legacyIdentity().parityScenario()).isEqualTo(scenario);
        assertThat(registration.inputSchema().definition().path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(registration.inputSchema().definition().path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.NOT_CANCELLABLE);
        assertThat(registration.safety().cancellationSupported()).isFalse();
    }

    @ParameterizedTest(name = "legacy normalization {0}")
    @MethodSource("ownedActions")
    void legacyAndCanonicalRoutesInvokeTheSameTypedOwner(ArtifactManagementAction action) {
        JsonNode input = input(action);
        ArtifactManagementRequest legacyRequest = new ArtifactManagementRequest(
                action.artifactType(), action.action(), input);
        ArtifactManagementRequest normalized = ArtifactOperationRegistrations.decode(
                action, new ArtifactOperationArguments(input));

        JsonNode legacy = mapper.valueToTree(legacyFeature.execute(legacyRequest));
        OperationExecutionResult canonical = execute(action, input);

        assertThat(normalized).isEqualTo(legacyRequest);
        assertThat(canonical.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(canonical.result()).as(action.routeId()).isEqualTo(legacy);
    }

    @Test
    void runResultLifecycleUsesRealArtifactAndSqliteState() {
        assertSuccess(ArtifactManagementAction.RUN_RESULT_UPSERT);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_READ);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_LIST);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_REBUILD);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_BACKFILL);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_CUTOVER);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_QUERY);
        assertSuccess(ArtifactManagementAction.RUN_RESULT_CLEANUP);

        assertThat(Files.isRegularFile(workspace.resolve(
                ".mcpjvm/demo/run-state.sqlite"))).isTrue();
        assertThat(Files.isRegularFile(workspace.resolve(
                ".mcpjvm/demo/plans/regression/health/runs/run-1/execution.result.json"))).isTrue();
    }

    @Test
    void canonicalSchemaAcceptsReleasedPerformanceAndExternalVerificationInputs() {
        ObjectNode performance = mapper.createObjectNode().put("projectName", "demo")
                .put("suiteType", "performance").put("planName", "load")
                .put("runId", "performance-1");
        OperationExecutionResult read = execute(ArtifactManagementAction.RUN_RESULT_READ, performance);
        assertThat(read.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(read.result().path("reasonCode").asText()).isEqualTo("success");

        assertSuccess(ArtifactManagementAction.RUN_RESULT_REBUILD);
        ObjectNode verificationQuery = mapper.createObjectNode().put("projectName", "demo")
                .put("stateSurface", "external_verification_state");
        OperationExecutionResult query = execute(
                ArtifactManagementAction.RUN_RESULT_QUERY, verificationQuery);

        assertThat(query.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(query.result().path("reasonCode").asText()).isEqualTo("success");
        assertThat(query.result().path("details").path("query").path("items").toString())
                .contains("performance-1");
    }

    @Test
    void executionExportGeneratesListsAndReadsDeterministicPackage() {
        OperationExecutionResult generated = execute(
                ArtifactManagementAction.EXECUTION_EXPORT_GENERATE,
                exportGenerateInput());
        assertThat(generated.result().path("reasonCode").asText()).isEqualTo("success");
        String exportId = generated.result().path("details").path("exportId").asText();
        assertThat(exportId).isNotBlank();

        JsonNode listed = execute(ArtifactManagementAction.EXECUTION_EXPORT_LIST,
                mapper.createObjectNode().put("projectName", "demo")).result();
        assertThat(listed.path("details").path("exportFolders")).contains(
                mapper.getNodeFactory().textNode(exportId));

        ObjectNode read = mapper.createObjectNode().put("projectName", "demo");
        read.putObject("query").put("exportId", exportId);
        JsonNode result = execute(ArtifactManagementAction.EXECUTION_EXPORT_READ, read).result();
        assertThat(result.path("reasonCode").asText()).isEqualTo("success");
        assertThat(result.path("details").path("exportId").asText()).isEqualTo(exportId);
        assertThat(Files.isRegularFile(workspace.resolve(
                ".mcpjvm/demo/exports/" + exportId + "/replay.sh"))).isTrue();
    }

    @Test
    void failsClosedForUnknownOversizedUnsafeAndUnconfirmedInputs() {
        OperationExecutionResult unknown = directory.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.RUN_RESULT_READ)),
                mapper.createObjectNode().put("unknown", true), false));
        assertThat(unknown.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);

        ObjectNode unsafe = runInput().put("runId", "../escape");
        JsonNode unsafeResult = execute(ArtifactManagementAction.RUN_RESULT_READ, unsafe).result();
        assertThat(unsafeResult.path("reasonCode").asText())
                .isEqualTo("artifact_path_segment_invalid");

        ObjectNode oversized = mapper.createObjectNode().put("projectName", "demo")
                .put("padding", "x".repeat(1_048_576));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> execute(ArtifactManagementAction.EXECUTION_EXPORT_LIST, oversized))
                .withMessageContaining("safety bound");

        OperationExecutionResult unconfirmed = directory.execute(new OperationInvocation(
                OperationId.of(operationId(ArtifactManagementAction.RUN_RESULT_CLEANUP)),
                input(ArtifactManagementAction.RUN_RESULT_CLEANUP), false));
        assertThat(unconfirmed.status()).isEqualTo(OperationExecutionStatus.CONFIRMATION_REQUIRED);
    }

    @Test
    void generatesElevenRowManifestAndTraceEvidence() throws Exception {
        assertThat(directory.manifest().descriptors()).hasSize(11);
        assertThat(directory.traceInventory()).hasSize(11).allSatisfy(entry ->
                assertThat(entry.compatibility()).containsKeys(
                        "normalization", "resultComparison", "parityScenario"));
        Path evidence = Path.of("target", "mcpjvm-619-evidence");
        Files.createDirectories(evidence);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest.json").toFile(), directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-inventory.json").toFile(), directory.traceInventory());
    }

    static Stream<ArtifactManagementAction> ownedActions() {
        return OWNED.stream();
    }

    private void assertSuccess(ArtifactManagementAction action) {
        OperationExecutionResult result = execute(action, input(action));
        assertThat(result.status()).as(action.routeId()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("status").asText()).isEqualTo("ok");
    }

    private OperationExecutionResult execute(ArtifactManagementAction action, JsonNode input) {
        return directory.execute(new OperationInvocation(
                OperationId.of(operationId(action)), input, mutating(action)));
    }

    private JsonNode input(ArtifactManagementAction action) {
        if (action.artifactType().value().equals("execution_export")) {
            return exportInput(action);
        }
        if (action == ArtifactManagementAction.RUN_RESULT_UPSERT) {
            return runInput().set("payload", mapper.createObjectNode().put("status", "pass"));
        }
        if (action == ArtifactManagementAction.RUN_RESULT_BACKFILL) {
            return mapper.createObjectNode().put("projectName", "demo")
                    .put("stateSurface", "correlation_state");
        }
        if (Set.of(ArtifactManagementAction.RUN_RESULT_REBUILD,
                ArtifactManagementAction.RUN_RESULT_CUTOVER,
                ArtifactManagementAction.RUN_RESULT_QUERY,
                ArtifactManagementAction.RUN_RESULT_CLEANUP).contains(action)) {
            return mapper.createObjectNode().put("projectName", "demo");
        }
        return runInput();
    }

    private JsonNode exportInput(ArtifactManagementAction action) {
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_GENERATE) {
            return exportGenerateInput();
        }
        ObjectNode input = mapper.createObjectNode().put("projectName", "demo");
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_READ) {
            input.putObject("query").put("exportId", "missing-export");
        }
        return input;
    }

    private ObjectNode runInput() {
        return mapper.createObjectNode().put("projectName", "demo")
                .put("suiteType", "regression").put("planName", "health")
                .put("runId", "run-1");
    }

    private ObjectNode exportGenerateInput() {
        return mapper.createObjectNode().put("projectName", "demo").put("mode", "sh")
                .put("planName", "health").put("executionProfile", "smoke");
    }

    private void seedArtifacts() throws Exception {
        Path project = workspace.resolve(".mcpjvm/demo");
        Files.createDirectories(project.resolve("plans/regression/health"));
        ObjectNode projects = mapper.createObjectNode();
        ObjectNode workspaceNode = projects.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString());
        workspaceNode.putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke").put("suiteType", "regression")
                .put("executionPolicy", "stop_on_fail").putArray("plans").addObject()
                .put("order", 1).put("planName", "health").put("onFail", "inherit");
        writeJson(project.resolve("projects.json"), projects);
        writeJson(project.resolve("plans/regression/health/metadata.json"),
                mapper.createObjectNode());
        ObjectNode contract = mapper.createObjectNode();
        contract.putArray("targets").addObject();
        contract.putArray("steps").addObject().put("id", "health")
                .put("protocol", "http").putObject("transport").putObject("http")
                .put("method", "GET").put("url", "http://127.0.0.1:9196/health");
        writeJson(project.resolve("plans/regression/health/contract.json"), contract);
        Path run = project.resolve("plans/regression/health/runs/run-1");
        Files.createDirectories(run);
        writeJson(run.resolve("execution.result.json"),
                mapper.createObjectNode().put("status", "pass"));
        Path performanceRun = project.resolve("plans/performance/load/runs/performance-1");
        Files.createDirectories(performanceRun);
        ObjectNode performanceResult = mapper.createObjectNode().put("status", "pass")
                .put("startedAt", 100).put("endedAt", 200);
        performanceResult.putObject("externalVerification").put("status", "pass");
        writeJson(performanceRun.resolve("execution.result.json"), performanceResult);
    }

    private void writeJson(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private boolean owned(OperationRegistration<?, ?> registration) {
        return OWNED.stream().map(ArtifactRunExportOperationRegistrationTest::operationId)
                .anyMatch(registration.descriptor().operationId().value()::equals);
    }

    private OperationManifestDocument ownedDocument() {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        Map<OperationId, com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation>
                documentation = new LinkedHashMap<>();
        all.operations().forEach((id, value) -> {
            if (registrations.containsKey(id.value())) {
                documentation.put(id, value);
            }
        });
        return new OperationManifestDocument(all.version(), documentation);
    }

    private static String scenario(ArtifactManagementAction action) {
        return "artifact_management_" + action.artifactType().value()
                + "_" + action.action().value();
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.routeId().replace('/', '.');
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return Set.of("upsert", "rebuild", "backfill", "cutover", "cleanup", "generate")
                .contains(action.action().value());
    }
}
