package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable contract evidence for MCPJVM-618's twelve Artifact plan rows. */
class ArtifactPlanOperationRegistrationTest {

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

    @TempDir
    Path outside;

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, OperationRegistration<?, ?>> registrations;
    private OperationDirectory directory;
    private ArtifactManagementFeature legacyFeature;

    @BeforeEach
    void setUp() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> java.util.Optional.of(workspace), new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support,
                () -> new com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry(List.of()));
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure("artifact_management", "mcpjvm-618",
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
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedActions")
    void registersExactOwnerIdentitySchemaAndCancellation(ArtifactManagementAction action) {
        OperationRegistration<?, ?> registration = registrations.get(operationId(action));

        assertThat(registration).isNotNull();
        assertThat(registration.descriptor().executableOwner())
                .startsWith(PlanOperations.class.getName());
        assertThat(registration.legacyIdentity().toolName()).isEqualTo("artifact_management");
        assertThat(registration.legacyIdentity().action()).isEqualTo(action.routeId());
        assertThat(registration.legacyIdentity().discriminators())
                .containsEntry("artifactType", action.artifactType().value())
                .containsEntry("action", action.action().value());
        String scenario = "artifact_management_" + action.artifactType().value()
                + "_" + action.action().value();
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

    @Test
    void executesReadValidateUpsertAndListForEveryPlanType() throws Exception {
        for (String suite : List.of("performance", "regression", "security")) {
            ObjectNode upsert = mapper.createObjectNode().put("projectName", "demo")
                    .put("planName", suite + "-plan");
            upsert.set("payload", payload(suite));
            assertSucceeded(id(suite, "upsert"), upsert, true);
            assertSucceeded(id(suite, "read"), selector(suite), false);
            assertSucceeded(id(suite, "validate"), selector(suite), false);
            JsonNode listed = assertSucceeded(id(suite, "list"),
                    mapper.createObjectNode().put("projectName", "demo"), false);
            assertThat(listed.path("details").path("planNames").get(0).asText())
                    .isEqualTo(suite + "-plan");
            Path root = workspace.resolve(".mcpjvm/demo/plans/" + suite + "/" + suite + "-plan");
            assertExactPersisted(root, payload(suite));
        }
    }

    @ParameterizedTest(name = "legacy normalization {0}")
    @MethodSource("ownedActions")
    void executableLegacyNormalizationMatchesCanonicalResultForEveryRow(
            ArtifactManagementAction action) throws Exception {
        if (!mutating(action)) {
            ObjectNode setup = mapper.createObjectNode().put("projectName", "demo")
                    .put("planName", "sample");
            setup.set("payload", payload(suite(action)));
            assertSucceeded(id(suite(action), "upsert"), setup, true);
        }
        JsonNode canonicalInput = input(action);
        ArtifactManagementRequest legacyRequest = new ArtifactManagementRequest(
                action.artifactType(), action.action(), canonicalInput);
        ArtifactManagementRequest normalized = ArtifactOperationRegistrations.decode(
                action, new ArtifactOperationArguments(canonicalInput));
        JsonNode legacy = mapper.valueToTree(legacyFeature.execute(legacyRequest));
        OperationExecutionResult canonical = execute(
                operationId(action), canonicalInput, mutating(action));

        assertThat(canonical.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(normalized).as(action.routeId()).isEqualTo(legacyRequest);
        assertThat(canonical.result()).as(action.routeId()).isEqualTo(legacy);
        if (mutating(action)) {
            assertExactPersisted(planRoot(suite(action), "sample"), payload(suite(action)));
        }
    }

    @Test
    void regressionReadMatchesReleasedDefaultAndWindowedProjection() {
        ObjectNode upsert = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "regression-plan");
        upsert.set("payload", payload("regression"));
        assertSucceeded(id("regression", "upsert"), upsert, true);

        JsonNode defaultRead = assertSucceeded(id("regression", "read"),
                mapper.createObjectNode().put("projectName", "demo")
                        .put("planName", "regression-plan"), false);
        assertThat(defaultRead.path("details")).isEqualTo(mapper.valueToTree(Map.of(
                "artifactType", "regression_plan",
                "action", "read",
                "projectName", "demo",
                "planName", "regression-plan",
                "summary", Map.of(
                        "intent", "regression",
                        "stepCount", 1,
                        "targetCount", 1,
                        "prerequisiteCount", 1))));

        ObjectNode selectedInput = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "regression-plan");
        ObjectNode query = selectedInput.putObject("query");
        query.putArray("select").add("summary").add("targets")
                .add("prerequisites").add("steps").add("metadata").add("contract").add("plan");
        query.putObject("prerequisites").put("offset", 0).put("limit", 1);
        query.putObject("steps").put("offset", 0).put("limit", 1);
        JsonNode selected = assertSucceeded(id("regression", "read"), selectedInput, false)
                .path("details");
        assertThat(selected.path("summary")).isEqualTo(
                defaultRead.path("details").path("summary"));
        assertThat(selected.path("targets")).hasSize(1);
        assertThat(selected.path("prerequisites").path("items")).hasSize(1);
        assertThat(selected.path("steps").path("items")).hasSize(1);
        assertThat(selected.path("artifact").path("metadata")
                .path("execution").path("intent").asText()).isEqualTo("regression");
        assertThat(selected.path("artifact").path("contract").path("steps")).hasSize(1);
        assertThat(selected.path("artifact").path("plan").asText())
                .isEqualTo("# regression plan\n");
    }

    @Test
    void regressionReadRejectsFractionalWindowValuesWithoutTruncation() {
        ObjectNode upsert = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "regression-plan");
        upsert.set("payload", payload("regression"));
        assertSucceeded(id("regression", "upsert"), upsert, true);

        for (double[] window : new double[][] {{0.5, 1}, {0, 1.5}}) {
            ObjectNode input = mapper.createObjectNode().put("projectName", "demo")
                    .put("planName", "regression-plan");
            ObjectNode query = input.putObject("query");
            ObjectNode steps = query.putObject("steps");
            steps.put("offset", window[0]);
            steps.put("limit", window[1]);
            query.putArray("select").add("steps");

            assertReason(id("regression", "read"), input, "window_query_required");
        }
    }

    @Test
    void listsMultiplePlansInStableLexicalOrderForEveryPlanType() throws Exception {
        for (String suite : List.of("performance", "regression", "security")) {
            for (String name : List.of("zeta", "alpha", "middle")) {
                ObjectNode upsert = mapper.createObjectNode().put("projectName", "demo")
                        .put("planName", name);
                upsert.set("payload", payload(suite));
                assertSucceeded(id(suite, "upsert"), upsert, true);
            }
            JsonNode listed = assertSucceeded(id(suite, "list"),
                    mapper.createObjectNode().put("projectName", "demo"), false);
            assertThat(listed.path("details").path("planNames")).containsExactly(
                    mapper.getNodeFactory().textNode("alpha"),
                    mapper.getNodeFactory().textNode("middle"),
                    mapper.getNodeFactory().textNode("zeta"));
        }
    }

    @Test
    void failsClosedForInvalidPlanPathContractAndOversizedInput() {
        ObjectNode unsafe = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "../escape").set("payload", payload("regression"));
        assertReason("artifact_management.regression_plan.upsert", unsafe,
                "artifact_path_segment_invalid");

        ObjectNode invalid = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "invalid");
        invalid.set("payload", mapper.createObjectNode()
                .set("metadata", mapper.createObjectNode()));
        assertReason("artifact_management.security_plan.upsert", invalid,
                "plan_contract_invalid");

        ObjectNode oversized = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "large").put("padding", "x".repeat(1_048_576));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> execute(
                        "artifact_management.regression_plan.read", oversized, false))
                .withMessageContaining("safety bound");
    }

    @ParameterizedTest(name = "missing and malformed {0}")
    @MethodSource("suiteTypes")
    void failsClosedForMissingAndMalformedPersistedPlanFiles(String suite) throws Exception {
        ObjectNode input = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "missing");
        assertReason(id(suite, "read"), input, "artifact_missing");
        assertReason(id(suite, "validate"), input, "artifact_missing");

        Path metadataInvalid = planRoot(suite, "metadata-invalid");
        Files.createDirectories(metadataInvalid);
        Files.writeString(metadataInvalid.resolve("metadata.json"), "{invalid");
        writeJson(metadataInvalid.resolve("contract.json"), payload(suite).path("contract"));
        ObjectNode metadataInput = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "metadata-invalid");
        assertReason(id(suite, "read"), metadataInput, "artifact_json_invalid");
        if ("performance".equals(suite) || "regression".equals(suite)) {
            assertReason(id(suite, "validate"), metadataInput, "artifact_json_invalid");
        }

        Path contractInvalid = planRoot(suite, "contract-invalid");
        Files.createDirectories(contractInvalid);
        writeJson(contractInvalid.resolve("metadata.json"), payload(suite).path("metadata"));
        Files.writeString(contractInvalid.resolve("contract.json"), "{invalid");
        ObjectNode contractInput = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "contract-invalid");
        assertReason(id(suite, "read"), contractInput, "artifact_json_invalid");
        assertReason(id(suite, "validate"), contractInput, "artifact_json_invalid");
    }

    @ParameterizedTest(name = "input boundary {0}")
    @MethodSource("ownedActions")
    void enforcesNullDefaultAndUnknownFieldBehaviorAcrossAllRows(
            ArtifactManagementAction action) throws Exception {
        OperationExecutionResult nullInput = directory.execute(new OperationInvocation(
                OperationId.of(operationId(action)), null, mutating(action)));
        assertThat(nullInput.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(nullInput.reasonCode()).isEqualTo("operation_input_schema_invalid");

        OperationExecutionResult unknown = execute(operationId(action),
                mapper.createObjectNode().put("unknown", true), mutating(action));
        assertThat(unknown.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(unknown.reasonCode()).isEqualTo("operation_input_schema_invalid");

        Path project = workspace.resolve(".mcpjvm/default-project/projects.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, "{}\n");
        OperationExecutionResult defaults = execute(operationId(action), mapper.createObjectNode(),
                mutating(action));
        assertThat(defaults.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        if (action.action().value().equals("list")) {
            assertThat(defaults.result().path("reasonCode").asText()).isEqualTo("success");
            assertThat(defaults.result().path("details").path("projectName").asText())
                    .isEqualTo("default-project");
            assertThat(defaults.result().path("details").path("planNames")).isEmpty();
        } else {
            assertThat(defaults.result().path("reasonCode").asText()).isEqualTo("plan_name_required");
            assertGenericCorrection(defaults.result());
        }
    }

    @ParameterizedTest(name = "symlink escape {0}")
    @MethodSource("ownedActions")
    void everyPlanRowRejectsPlanRootSymlinkEscape(ArtifactManagementAction action) throws Exception {
        Path link = workspace.resolve(".mcpjvm/demo/plans").resolve(suite(action));
        Files.createDirectories(link.getParent());
        assumeDirectoryLink(link, outside);

        OperationExecutionResult result = execute(operationId(action), input(action), mutating(action));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("reasonCode").asText())
                .isEqualTo("artifact_path_symlink_escape");
        assertGenericCorrection(result.result());
        try (Stream<Path> entries = Files.list(outside)) {
            assertThat(entries.findAny()).isEmpty();
        }
    }

    @ParameterizedTest(name = "file symlink escape {0}/{1}")
    @CsvSource({
            "performance, metadata.json", "performance, contract.json", "performance, plan.md",
            "regression, metadata.json", "regression, contract.json", "regression, plan.md",
            "security, metadata.json", "security, contract.json", "security, plan.md"
    })
    void planReadsRejectEveryFileLevelSymlinkEscape(String suite, String file) throws Exception {
        ObjectNode upsert = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "file-link");
        upsert.set("payload", payload(suite));
        assertSucceeded(id(suite, "upsert"), upsert, true);
        Path linkedFile = planRoot(suite, "file-link").resolve(file);
        Path outsideFile = outside.resolve(suite + "-" + file.replace('.', '-'));
        Files.writeString(outsideFile, "{\"outside\":true}\n");
        assumeFileLink(linkedFile, outsideFile);

        ObjectNode input = mapper.createObjectNode().put("projectName", "demo")
                .put("planName", "file-link");
        if ("regression".equals(suite) && "plan.md".equals(file)) {
            input.putObject("query").putArray("select").add("plan");
        }
        assertReason(id(suite, "read"), input, "artifact_path_symlink_escape");
        assertThat(Files.readString(outsideFile)).isEqualTo("{\"outside\":true}\n");
    }

    @Test
    void generatesTwelveRowManifestAndTraceEvidence() throws Exception {
        assertThat(directory.manifest().descriptors()).hasSize(12);
        assertThat(directory.traceInventory()).hasSize(12).allSatisfy(entry -> {
            assertThat(entry.compatibility()).containsKeys(
                    "normalization", "resultComparison", "parityScenario");
            assertThat(entry.executableOwner()).startsWith(PlanOperations.class.getName());
        });
        Path evidence = Path.of("target", "mcpjvm-618-evidence");
        Files.createDirectories(evidence);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest.json").toFile(), directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-inventory.json").toFile(), directory.traceInventory());
    }

    static Stream<ArtifactManagementAction> ownedActions() {
        return OWNED.stream();
    }

    static Stream<String> suiteTypes() {
        return Stream.of("performance", "regression", "security");
    }

    private ObjectNode selector(String suite) {
        return mapper.createObjectNode().put("projectName", "demo")
                .put("planName", suite + "-plan");
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
            metadata.putObject("execution").put("intent", "regression");
            contract.putArray("targets").addObject().put("id", "health-target");
            contract.putArray("prerequisites").addObject().put("id", "ready");
            contract.putArray("steps").addObject().put("id", "health").put("protocol", "http");
        } else {
            contract.put("suiteType", "security");
        }
        payload.put("plan", "# " + suite + " plan\n");
        return payload;
    }

    private void assertExactPersisted(Path root, JsonNode expected) throws Exception {
        String metadata = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(expected.path("metadata")) + "\n";
        String contract = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(expected.path("contract")) + "\n";
        assertThat(Files.readString(root.resolve("metadata.json"))).isEqualTo(metadata);
        assertThat(Files.readString(root.resolve("contract.json"))).isEqualTo(contract);
        assertThat(Files.readString(root.resolve("plan.md")))
                .isEqualTo(expected.path("plan").asText());
    }

    private Path planRoot(String suite, String planName) {
        return workspace.resolve(".mcpjvm/demo/plans").resolve(suite).resolve(planName);
    }

    private void writeJson(Path path, JsonNode value) throws Exception {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
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

    private JsonNode assertSucceeded(String id, JsonNode input, boolean confirmed) {
        OperationExecutionResult result = execute(id, input, confirmed);
        assertThat(result.status()).as(id + ": " + result).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("reasonCode").asText()).isEqualTo("success");
        assertThat(result.result().path("nextActionCode").isNull()).isTrue();
        assertThat(result.result().path("nextAction").isNull()).isTrue();
        return result.result();
    }

    private void assertReason(String id, JsonNode input, String reasonCode) {
        OperationExecutionResult result = execute(id, input, true);
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("reasonCode").asText()).isEqualTo(reasonCode);
        assertGenericCorrection(result.result());
    }

    private void assertGenericCorrection(JsonNode result) {
        assertThat(result.path("nextActionCode").asText())
                .isEqualTo("correct the Artifact input or persisted state and retry");
        assertThat(result.path("nextAction").asText()).isEqualTo(result.path("reason").asText());
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

    private static void assumeFileLink(Path link, Path target) throws Exception {
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // A junction at the exact file path still proves the per-file containment check.
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Assumptions.assumeTrue(false, "file links are unavailable in this environment");
        }
        Path junctionTarget = target.resolveSibling(target.getFileName() + "-junction");
        Files.createDirectories(junctionTarget);
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), junctionTarget.toString()).redirectErrorStream(true).start();
        try {
            if (process.waitFor() != 0) {
                Assumptions.assumeTrue(false, "file links are unavailable in this environment");
            }
        } finally {
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
            process.destroy();
        }
    }

    private OperationExecutionResult execute(String id, JsonNode input, boolean confirmed) {
        return directory.execute(new OperationInvocation(OperationId.of(id), input, confirmed));
    }

    private boolean owned(OperationRegistration<?, ?> registration) {
        return OWNED.stream().map(ArtifactPlanOperationRegistrationTest::operationId)
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

    private static String id(String suite, String action) {
        return "artifact_management." + suite + "_plan." + action;
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return "upsert".equals(action.action().value());
    }

    private static String suite(ArtifactManagementAction action) {
        return action.artifactType().value().replace("_plan", "");
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.routeId().replace('/', '.');
    }
}
