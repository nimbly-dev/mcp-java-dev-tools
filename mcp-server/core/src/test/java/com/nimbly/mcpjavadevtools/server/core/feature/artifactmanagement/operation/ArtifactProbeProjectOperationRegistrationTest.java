package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.DefaultArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryReloader;
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
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable contract evidence for MCPJVM-617's eight Artifact rows. */
class ArtifactProbeProjectOperationRegistrationTest {

    private static final Set<ArtifactManagementAction> OWNED_ACTIONS = Set.of(
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

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger reloads = new AtomicInteger();
    private final TestRegistrySource registrySource = new TestRegistrySource();
    private Map<String, OperationRegistration<?, ?>> registrations;
    private OperationDirectory directory;

    @BeforeEach
    void setUp() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> java.util.Optional.of(workspace), new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support, registrySource);
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure(ArtifactOperationCatalog.TOOL_NAME, "mcpjvm-617",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        List<OperationRegistration<?, ?>> owned = ArtifactOperationRegistrations.create(catalog, mapper)
                .stream().filter(this::owned).toList();
        registrations = owned.stream().collect(java.util.stream.Collectors.toMap(
                registration -> registration.descriptor().operationId().value(),
                registration -> registration,
                (left, right) -> left,
                LinkedHashMap::new));
        OperationManifestDocument document = ownedDocument();
        OperationManifestAssembler.assembleStrict(owned, document);
        directory = new OperationDirectory(owned, document, mapper);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedActions")
    void registersOneDirectOwnerWithCanonicalIdentityAndExplicitCancellation(
            ArtifactManagementAction action) {
        String operationId = operationId(action);
        OperationRegistration<?, ?> registration = registrations.get(operationId);

        assertThat(registration).isNotNull();
        assertThat(registration.descriptor().executableOwner())
                .startsWith(action.artifactType().value().equals("probe_config")
                        ? ProbeConfigOperations.class.getName()
                        : ProjectContextOperations.class.getName());
        assertThat(registration.provenance().invocationTool()).isEqualTo("artifact_management");
        assertThat(registration.provenance().invocationAction()).isEqualTo(action.routeId());
        assertThat(registration.provenance().discriminators())
                .containsEntry("artifactType", action.artifactType().value())
                .containsEntry("action", action.action().value());
        String scenario = "artifact_management_" + action.artifactType().value()
                + "_" + action.action().value();
        assertThat(registration.provenance().normalization()).contains(scenario);
        assertThat(registration.provenance().resultComparison()).contains(scenario)
                .contains("persisted_artifacts");
        assertThat(registration.provenance().parityScenario()).isEqualTo(scenario);
        assertThat(registration.inputSchema().definition().path("properties").has("artifactType"))
                .isFalse();
        assertThat(registration.inputSchema().definition().path("properties").has("action"))
                .isFalse();
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.NOT_CANCELLABLE);
    }

    @Test
    void generatesEightRowManifestAndTraceCompletionEvidence() throws Exception {
        Set<String> expectedIds = OWNED_ACTIONS.stream()
                .map(ArtifactProbeProjectOperationRegistrationTest::operationId)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(directory.manifest().descriptors())
                .extracting(descriptor -> descriptor.operationId().value())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(directory.traceInventory())
                .extracting(entry -> entry.operationId())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(directory.traceInventory()).allSatisfy(entry -> {
            assertThat(entry.compatibility()).containsKeys(
                    "normalization", "resultComparison", "parityScenario");
            assertThat(entry.executableOwner()).isNotBlank();
        });

        Path evidence = Path.of("target", "mcpjvm-617-evidence");
        Files.createDirectories(evidence);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest.json").toFile(), directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-inventory.json").toFile(), directory.traceInventory());
    }

    @Test
    void executesAllEightRowsAgainstOneRealWorkspace() throws Exception {
        JsonNode missing = assertCapability("artifact_management.probe_config.read",
                mapper.createObjectNode(), false, "not_configured", "probe_registry_not_configured");
        assertThat(missing.path("nextActionCode").asText()).isEqualTo("set_probe_registry_config");

        ObjectNode probePayload = validProbe();
        JsonNode probeUpsert = assertOk("artifact_management.probe_config.upsert",
                mapper.createObjectNode().set("payload", probePayload), true);
        assertThat(probeUpsert.path("details").path("reloadApplied").asBoolean()).isTrue();
        assertThat(probeUpsert.path("details").path("activeProbeCount").asInt()).isEqualTo(1);
        JsonNode probeRead = assertOk(
                "artifact_management.probe_config.read", mapper.createObjectNode(), false);
        assertThat(probeRead.path("details").path("artifact")).isEqualTo(probePayload);
        assertThat(probeRead.path("details").path("activeProfile").asText()).isEqualTo("local");
        assertThat(probeRead.path("details").path("profileSource").asText()).isEqualTo("default");
        assertThat(probeRead.path("details").path("probeCount").asInt()).isEqualTo(1);
        assertThat(probeRead.path("details").path("probes").path(0).path("id").asText())
                .isEqualTo("local");
        assertThat(assertOk("artifact_management.probe_config.validate",
                mapper.createObjectNode(), false).path("details").path("probeCount").asInt())
                .isEqualTo(1);
        JsonNode reload = assertCapability("artifact_management.probe_config.reload",
                mapper.createObjectNode(), true, "reloaded", "success");
        assertThat(reload.path("details").path("reloadApplied").asBoolean()).isTrue();
        assertThat(reload.path("details").path("activeProbeCount").asInt()).isEqualTo(1);

        ObjectNode project = validProject();
        for (String projectName : List.of("sample", "zeta", "alpha")) {
            ObjectNode upsert = mapper.createObjectNode().put("projectName", projectName);
            upsert.set("payload", project);
            JsonNode result = assertOk("artifact_management.project_context.upsert", upsert, true);
            assertThat(result.path("details").path("projectName").asText()).isEqualTo(projectName);
            assertThat(result.path("details").path("updateMode").asText()).isEqualTo("created");
            assertThat(result.path("details").path("stateStore").path("provisioned").asBoolean()).isTrue();
            assertThat(Files.isRegularFile(workspace.resolve(
                    ".mcpjvm/" + projectName + "/run-state.sqlite"))).isTrue();
        }
        ObjectNode readInput = mapper.createObjectNode().put("projectName", "sample");
        readInput.putObject("query").putArray("select").add("artifact");
        JsonNode projectRead = assertOk(
                "artifact_management.project_context.read", readInput, false);
        assertThat(projectRead.path("details").path("artifact")).isEqualTo(project);
        JsonNode validated = assertOk("artifact_management.project_context.validate",
                mapper.createObjectNode().put("projectName", "sample")
                        .put("projectRootAbs", workspace.toString()), false);
        assertThat(validated.path("details").path("valid").asBoolean()).isTrue();
        assertThat(validated.path("details").path("workspaceCount").asInt()).isEqualTo(1);
        JsonNode first = assertOk(
                "artifact_management.project_context.list", mapper.createObjectNode(), false);
        JsonNode second = assertOk(
                "artifact_management.project_context.list", mapper.createObjectNode(), false);

        assertThat(first).isEqualTo(second);
        assertThat(first.path("details").path("projectNames"))
                .containsExactly(mapper.getNodeFactory().textNode("alpha"),
                        mapper.getNodeFactory().textNode("sample"),
                        mapper.getNodeFactory().textNode("zeta"));
        assertThat(reloads).hasValue(2);
        assertThat(mapper.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile()))
                .isEqualTo(probeRead.path("details").path("artifact"));
        assertThat(mapper.readTree(workspace.resolve(".mcpjvm/sample/projects.json").toFile()))
                .isEqualTo(project);
    }

    @Test
    void rejectsUnknownMissingNullAndOversizedCanonicalInput() {
        assertStatus("artifact_management.probe_config.read",
                mapper.createObjectNode().put("artifactType", "probe_config"),
                OperationExecutionStatus.INVALID_INPUT);
        assertStatus("artifact_management.probe_config.upsert", mapper.createObjectNode(),
                OperationExecutionStatus.INVALID_INPUT);
        assertStatus("artifact_management.project_context.upsert",
                mapper.createObjectNode().putNull("projectName").putObject("payload"),
                OperationExecutionStatus.INVALID_INPUT);
        ObjectNode oversized = mapper.createObjectNode();
        oversized.putObject("payload").put("value", "x".repeat(1_048_576));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> execute("artifact_management.probe_config.upsert", oversized, true))
                .withMessageContaining("safety bound");
    }

    @ParameterizedTest(name = "schema {0}")
    @MethodSource("schemaCases")
    void publishesExactClosedCanonicalSchemaAndEnforcesNullability(SchemaCase expected) {
        OperationRegistration<?, ?> registration = registrations.get(operationId(expected.action()));
        JsonNode schema = registration.inputSchema().definition();
        JsonNode properties = schema.path("properties");

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(properties)).containsExactlyInAnyOrderElementsOf(expected.properties());
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrderElementsOf(expected.required());
        if (expected.action() == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            assertThat(properties.path("replace").path("default").asBoolean()).isFalse();
        }

        ObjectNode unknown = mapper.createObjectNode().put("unexpected", true);
        assertThat(OperationSchemaValidator.violations(registration.inputSchema(), unknown)).isNotEmpty();
        for (String property : expected.properties()) {
            ObjectNode nullInput = validSchemaInput(expected.action());
            nullInput.putNull(property);
            assertThat(OperationSchemaValidator.violations(registration.inputSchema(), nullInput))
                    .as("%s.%s must reject null", expected.action(), property).isNotEmpty();
        }
        for (String required : expected.required()) {
            ObjectNode missing = validSchemaInput(expected.action());
            missing.remove(required);
            assertThat(OperationSchemaValidator.violations(registration.inputSchema(), missing))
                    .as("%s must require %s", expected.action(), required).isNotEmpty();
        }
    }

    @ParameterizedTest(name = "parity {0}")
    @MethodSource("ownedActions")
    void executableCanonicalAdapterMatchesLegacyOwnerAndPersistedState(
            ArtifactManagementAction action) throws Exception {
        Path canonicalRoot = Files.createDirectory(workspace.resolve("canonical-" + action.name()));
        Path legacyRoot = Files.createDirectory(workspace.resolve("legacy-" + action.name()));
        Harness canonical = harness(canonicalRoot);
        Harness legacy = harness(legacyRoot);
        seed(action, canonicalRoot);
        seed(action, legacyRoot);
        ObjectNode input = parityInput(action);

        OperationExecutionResult canonicalResult = canonical.directory().execute(new OperationInvocation(
                OperationId.of(operationId(action)), input, mutating(action)));
        ArtifactManagementResult legacyResult = legacy.legacy().execute(new ArtifactManagementRequest(
                action.artifactType(), action.action(), input));

        assertThat(canonicalResult.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(normalizeResult(canonicalResult.result()))
                .isEqualTo(normalizeResult(mapper.valueToTree(legacyResult)));
        assertPersistedParity(action, canonicalRoot, legacyRoot);
    }

    @Test
    void rejectsProjectArtifactsThatViolateReleasedOrchestratorDefaults() {
        ObjectNode invalid = mapper.createObjectNode();
        invalid.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString()).putObject("defaults");
        ObjectNode input = mapper.createObjectNode().put("projectName", "invalid");
        input.set("payload", invalid);

        OperationExecutionResult execution = execute(
                "artifact_management.project_context.upsert", input, true);
        assertThat(execution.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        JsonNode result = execution.result();

        assertThat(result.path("status").asText()).isEqualTo("project_artifact_invalid");
        assertThat(result.path("reasonCode").asText()).isEqualTo("project_artifact_invalid");
        assertThat(result.path("reasonMeta").path("artifactType").asText())
                .isEqualTo("project_context");
        assertThat(result.path("reasonMeta").path("action").asText()).isEqualTo("upsert");
        assertThat(result.path("reason").asText()).contains("defaults.orchestrator");
        assertThat(Files.exists(workspace.resolve(".mcpjvm/invalid/projects.json"))).isFalse();
        assertThat(Files.exists(workspace.resolve(".mcpjvm/invalid/run-state.sqlite"))).isFalse();
    }

    @Test
    void rejectsRuntimeContextsRejectedByReleasedTypeScriptContract() {
        ObjectNode invalid = validProject();
        ((ObjectNode) invalid.withArray("workspaces").get(0)).putArray("runtimeContexts")
                .addObject().put("name", "bad").put("mode", "invalid");
        ObjectNode input = mapper.createObjectNode().put("projectName", "invalid-runtime");
        input.set("payload", invalid);

        OperationExecutionResult execution = execute(
                "artifact_management.project_context.upsert", input, true);

        assertThat(execution.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(execution.result().path("status").asText()).isEqualTo("runtime_context_unknown");
        assertThat(execution.result().path("reasonCode").asText()).isEqualTo("runtime_context_unknown");
        assertThat(execution.result().path("reason").asText()).contains("mode must be terminal|docker");
        assertThat(Files.exists(workspace.resolve(".mcpjvm/invalid-runtime/projects.json"))).isFalse();
        assertThat(Files.exists(workspace.resolve(".mcpjvm/invalid-runtime/run-state.sqlite"))).isFalse();
    }

    @Test
    void selectsOneOfMultipleProjectsByProjectRootAbs() throws Exception {
        Path alphaRoot = Files.createDirectory(workspace.resolve("alpha-root"));
        Path betaRoot = Files.createDirectory(workspace.resolve("beta-root"));
        ArtifactJsonStore store = new ArtifactJsonStore(mapper);
        store.write(workspace.resolve(".mcpjvm/alpha/projects.json"), validProject(alphaRoot));
        store.write(workspace.resolve(".mcpjvm/beta/projects.json"), validProject(betaRoot));

        ObjectNode input = mapper.createObjectNode().put("projectRootAbs", betaRoot.toString());
        input.putObject("query").putArray("select").add("artifact");
        JsonNode result = assertOk("artifact_management.project_context.read", input, false);

        assertThat(result.path("details").path("projectName").asText()).isEqualTo("beta");
        assertThat(result.path("details").path("artifact").path("workspaces").path(0)
                .path("projectRoot").asText()).isEqualTo(betaRoot.toString());
    }

    @Test
    void mergePreservesExistingUnmatchedWorkspaces() throws Exception {
        Path rootA = Files.createDirectory(workspace.resolve("merge-a"));
        Path rootB = Files.createDirectory(workspace.resolve("merge-b"));
        ObjectNode existing = mapper.createObjectNode();
        existing.putArray("workspaces").add(workspaceEntry(rootA, 1)).add(workspaceEntry(rootB, 2));
        ObjectNode create = mapper.createObjectNode().put("projectName", "merged");
        create.set("payload", existing);
        assertOk("artifact_management.project_context.upsert", create, true);

        ObjectNode incoming = mapper.createObjectNode();
        incoming.putArray("workspaces").add(workspaceEntry(rootA, 9));
        ObjectNode update = mapper.createObjectNode().put("projectName", "merged");
        update.set("payload", incoming);
        JsonNode result = assertOk("artifact_management.project_context.upsert", update, true);
        JsonNode persisted = mapper.readTree(
                workspace.resolve(".mcpjvm/merged/projects.json").toFile());

        assertThat(result.path("details").path("updateMode").asText()).isEqualTo("merged");
        assertThat(persisted.path("workspaces")).hasSize(2);
        assertThat(persisted.path("workspaces").path(0).path("defaults")
                .path("orchestrator").path("resumePollMax").asInt()).isEqualTo(9);
        assertThat(persisted.path("workspaces").path(1).path("projectRoot").asText())
                .isEqualTo(rootB.toString());
    }

    @ParameterizedTest(name = "invalid section {0}")
    @MethodSource("invalidProjectSections")
    void rejectsMalformedReleasedProjectSections(String section, String reasonCode) {
        ObjectNode invalid = validProject();
        ((ObjectNode) invalid.withArray("workspaces").get(0)).putArray(section).addObject();
        ObjectNode input = mapper.createObjectNode().put("projectName", "invalid-" + section);
        input.set("payload", invalid);

        JsonNode result = execute(
                "artifact_management.project_context.upsert", input, true).result();

        assertThat(result.path("status").asText()).isEqualTo(reasonCode);
        assertThat(result.path("reasonCode").asText()).isEqualTo(reasonCode);
        assertThat(Files.exists(workspace.resolve(
                ".mcpjvm/invalid-" + section + "/projects.json"))).isFalse();
    }

    @Test
    void safelyContainsNewStateStoreWhenProjectArtifactWriteFails() throws Exception {
        Path projectFile = workspace.resolve(".mcpjvm/write-fail/projects.json");
        Files.createDirectories(projectFile);
        ObjectNode input = mapper.createObjectNode().put("projectName", "write-fail");
        input.set("payload", validProject());

        JsonNode result = execute(
                "artifact_management.project_context.upsert", input, true).result();

        assertThat(result.path("status").asText()).isEqualTo("project_artifact_write_failed");
        String cleanup = result.path("reasonMeta").path("stateStoreCleanup").asText();
        assertThat(cleanup).isIn("removed", "preserved");
        assertThat(Files.exists(workspace.resolve(".mcpjvm/write-fail/run-state.sqlite")))
                .isEqualTo("preserved".equals(cleanup));
        Files.deleteIfExists(projectFile);
    }

    @Test
    void cleanupPreservesCommitFromWriterOutsideTheJavaLockConvention() throws Exception {
        Path database = workspace.resolve(".mcpjvm/concurrent/run-state.sqlite");
        SqliteRunStateStore store = new SqliteRunStateStore();
        store.ensure(database, "concurrent");
        try (var writer = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = writer.createStatement()) {
            statement.execute("CREATE TABLE external_writer(value TEXT NOT NULL)");
            statement.execute("BEGIN IMMEDIATE");
            statement.execute("INSERT INTO external_writer(value) VALUES('committed')");

            assertThat(store.cleanupProvisioning(workspace, database, "concurrent")).isFalse();
            statement.execute("COMMIT");
            try (var result = statement.executeQuery("SELECT value FROM external_writer")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("committed");
            }
        }
        assertThat(Files.isRegularFile(database)).isTrue();
    }

    @Test
    void probeReadPreservesActiveSummaryWhenPersistedFileIsInvalid() throws Exception {
        ObjectNode input = mapper.createObjectNode().set("payload", validProbe());
        assertOk("artifact_management.probe_config.upsert", input, true);
        Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), "{invalid");

        JsonNode result = assertOk(
                "artifact_management.probe_config.read", mapper.createObjectNode(), false);

        assertThat(result.path("details").has("artifact")).isFalse();
        assertThat(result.path("details").path("activeProfile").asText()).isEqualTo("local");
        assertThat(result.path("details").path("probeCount").asInt()).isEqualTo(1);
    }

    @Test
    void probeReadPreservesActiveSummaryWhenPersistedFileDisappears() throws Exception {
        ObjectNode input = mapper.createObjectNode().set("payload", validProbe());
        assertOk("artifact_management.probe_config.upsert", input, true);
        assertOk("artifact_management.probe_config.read", mapper.createObjectNode(), false);
        Files.delete(workspace.resolve(".mcpjvm/probe-config.json"));

        JsonNode result = assertOk(
                "artifact_management.probe_config.read", mapper.createObjectNode(), false);

        assertThat(result.path("details").has("artifact")).isFalse();
        assertThat(result.path("details").path("activeProfile").asText()).isEqualTo("local");
        assertThat(result.path("details").path("probeCount").asInt()).isEqualTo(1);
    }

    @Test
    void probeValidationRejectsReleasedDefaultProbeProperty() {
        ObjectNode probe = validProbe();
        ((ObjectNode) probe.path("profiles").path("local")).put("defaultProbe", "local");
        new ArtifactJsonStore(mapper).write(
                workspace.resolve(".mcpjvm/probe-config.json"), probe);

        JsonNode result = execute(
                "artifact_management.probe_config.validate", mapper.createObjectNode(), false).result();

        assertThat(result.path("status").asText()).isEqualTo("probe_config_invalid");
        assertThat(result.path("reasonCode").asText()).isEqualTo("probe_config_invalid");
    }

    @Test
    void failsClosedForMissingAndDuplicateOwnedRows() {
        List<OperationRegistration<?, ?>> owned = registrations.values().stream().toList();
        OperationManifestDocument document = ownedDocument();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        owned.subList(0, owned.size() - 1), document))
                .withMessageContaining("orphan operation documentation");
        List<OperationRegistration<?, ?>> duplicate = new java.util.ArrayList<>(owned);
        duplicate.add(owned.getFirst());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(duplicate, document))
                .withMessageContaining("duplicate executable operation");
    }

    static Stream<ArtifactManagementAction> ownedActions() {
        return Arrays.stream(ArtifactManagementAction.values()).filter(OWNED_ACTIONS::contains);
    }

    static Stream<SchemaCase> schemaCases() {
        return Stream.of(
                new SchemaCase(ArtifactManagementAction.PROBE_CONFIG_READ, Set.of(), Set.of()),
                new SchemaCase(ArtifactManagementAction.PROBE_CONFIG_VALIDATE, Set.of(), Set.of()),
                new SchemaCase(ArtifactManagementAction.PROBE_CONFIG_UPSERT,
                        Set.of("payload"), Set.of("payload")),
                new SchemaCase(ArtifactManagementAction.PROBE_CONFIG_RELOAD, Set.of(), Set.of()),
                new SchemaCase(ArtifactManagementAction.PROJECT_CONTEXT_READ,
                        Set.of("projectName", "projectRootAbs", "query"), Set.of()),
                new SchemaCase(ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE,
                        Set.of("projectName", "projectRootAbs"), Set.of()),
                new SchemaCase(ArtifactManagementAction.PROJECT_CONTEXT_UPSERT,
                        Set.of("projectName", "payload", "replace"), Set.of("projectName", "payload")),
                new SchemaCase(ArtifactManagementAction.PROJECT_CONTEXT_LIST, Set.of(), Set.of()));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidProjectSections() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "scripts", "project_artifact_invalid"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "runPrerequisites", "project_artifact_invalid"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "externalSystems", "external_system_invalid"));
    }

    private boolean owned(OperationRegistration<?, ?> registration) {
        return OWNED_ACTIONS.stream().map(ArtifactProbeProjectOperationRegistrationTest::operationId)
                .anyMatch(registration.descriptor().operationId().value()::equals);
    }

    private OperationManifestDocument ownedDocument() {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        Map<OperationId, com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation>
                documentation = new LinkedHashMap<>();
        all.operations().forEach((id, value) -> {
            if (registrations == null || registrations.containsKey(id.value())) {
                documentation.put(id, value);
            }
        });
        if (registrations == null) {
            documentation.entrySet().removeIf(entry -> OWNED_ACTIONS.stream()
                    .map(ArtifactProbeProjectOperationRegistrationTest::operationId)
                    .noneMatch(entry.getKey().value()::equals));
        }
        return new OperationManifestDocument(all.version(), documentation);
    }

    private JsonNode assertOk(String id, JsonNode input, boolean confirmed) {
        return assertCapability(id, input, confirmed, "ok", "success");
    }

    private JsonNode assertCapability(
            String id, JsonNode input, boolean confirmed, String capabilityStatus, String reasonCode) {
        OperationExecutionResult result = execute(id, input, confirmed);
        assertThat(result.status()).as(id).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        JsonNode payload = result.result();
        assertThat(payload.path("status").asText()).as(id).isEqualTo(capabilityStatus);
        assertThat(payload.path("reasonCode").asText()).as(id).isEqualTo(reasonCode);
        String[] parts = id.split("\\.");
        assertThat(payload.path("details").path("artifactType").asText()).isEqualTo(parts[1]);
        assertThat(payload.path("details").path("action").asText()).isEqualTo(parts[2]);
        return payload;
    }

    private void assertStatus(String id, JsonNode input, OperationExecutionStatus status) {
        assertThat(execute(id, input, true).status()).as(id).isEqualTo(status);
    }

    private OperationExecutionResult execute(String id, JsonNode input, boolean confirmed) {
        return directory.execute(new OperationInvocation(OperationId.of(id), input, confirmed));
    }

    private ObjectNode validProject() {
        return validProject(workspace);
    }

    private ObjectNode validProject(Path projectRoot) {
        ObjectNode project = mapper.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", projectRoot.toString())
                .set("defaults", validDefaults());
        return project;
    }

    private ObjectNode validDefaults() {
        return mapper.createObjectNode().set("orchestrator", mapper.createObjectNode()
                .put("resumePollMax", 1)
                .put("resumePollIntervalMs", 10)
                .put("resumePollTimeoutMs", 100));
    }

    private ObjectNode validProbe() {
        ObjectNode probe = mapper.createObjectNode();
        probe.put("defaultProfile", "local");
        ObjectNode entry = probe.putObject("profiles").putObject("local")
                .putObject("probes").putObject("local");
        entry.put("baseUrl", "http://127.0.0.1:9191");
        entry.put("description", "Local Probe");
        entry.putArray("include").add("com.example");
        entry.putArray("exclude").add("com.example.generated");
        return probe;
    }

    private ObjectNode workspaceEntry(Path root, int pollMax) {
        ObjectNode entry = mapper.createObjectNode().put("projectRoot", root.toString());
        entry.set("defaults", mapper.createObjectNode().set("orchestrator", mapper.createObjectNode()
                .put("resumePollMax", pollMax)
                .put("resumePollIntervalMs", 10)
                .put("resumePollTimeoutMs", 100)));
        return entry;
    }

    private Harness harness(Path root) {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> java.util.Optional.of(root), new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(), mapper);
        ProbeConfigOperations probe = new ProbeConfigOperations(support,
                () -> new ProbeRegistry(List.of(new ProbeRegistration(
                        "local", "http://127.0.0.1:9191"))));
        ArtifactOperationCatalog catalog = new ArtifactOperationCatalog(
                probe, new ProjectContextOperations(support), new PlanOperations(support),
                new RunResultOperations(support), new ExecutionExportOperations(support),
                new OperationExposure(ArtifactOperationCatalog.TOOL_NAME, "mcpjvm-617-parity",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        List<OperationRegistration<?, ?>> all = ArtifactOperationRegistrations.create(catalog, mapper)
                .stream().filter(this::owned).toList();
        OperationManifestDocument manifest = ownedDocument();
        OperationManifestAssembler.assembleStrict(all, manifest);
        return new Harness(new OperationDirectory(all, manifest, mapper),
                new DefaultArtifactManagementFeature(catalog));
    }

    private void seed(ArtifactManagementAction action, Path root) {
        ArtifactJsonStore store = new ArtifactJsonStore(mapper);
        if (action.artifactType().value().equals("probe_config")
                && action != ArtifactManagementAction.PROBE_CONFIG_UPSERT) {
            store.write(root.resolve(".mcpjvm/probe-config.json"), validProbe());
        }
        if (action.artifactType().value().equals("project_context")
                && action != ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            List<String> names = action == ArtifactManagementAction.PROJECT_CONTEXT_LIST
                    ? List.of("zeta", "alpha") : List.of("sample");
            for (String name : names) {
                store.write(root.resolve(".mcpjvm/" + name + "/projects.json"), validProject());
            }
        }
    }

    private ObjectNode parityInput(ArtifactManagementAction action) {
        ObjectNode input = mapper.createObjectNode();
        if (action == ArtifactManagementAction.PROBE_CONFIG_UPSERT) {
            input.set("payload", validProbe());
        } else if (action == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            input.put("projectName", "sample").set("payload", validProject());
        } else if (action == ArtifactManagementAction.PROJECT_CONTEXT_READ) {
            input.put("projectName", "sample").putObject("query").putArray("select").add("artifact");
        } else if (action == ArtifactManagementAction.PROJECT_CONTEXT_VALIDATE) {
            input.put("projectName", "sample");
        }
        return input;
    }

    private void assertPersistedParity(
            ArtifactManagementAction action, Path canonicalRoot, Path legacyRoot) {
        if (action.artifactType().value().equals("probe_config")) {
            assertThat(new ArtifactJsonStore(mapper).read(
                    canonicalRoot.resolve(".mcpjvm/probe-config.json")))
                    .isEqualTo(new ArtifactJsonStore(mapper).read(
                            legacyRoot.resolve(".mcpjvm/probe-config.json")));
        } else if (action == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            assertThat(new ArtifactJsonStore(mapper).read(
                    canonicalRoot.resolve(".mcpjvm/sample/projects.json")))
                    .isEqualTo(new ArtifactJsonStore(mapper).read(
                            legacyRoot.resolve(".mcpjvm/sample/projects.json")));
            assertThat(Files.isRegularFile(canonicalRoot.resolve(
                    ".mcpjvm/sample/run-state.sqlite"))).isTrue();
            assertThat(Files.isRegularFile(legacyRoot.resolve(
                    ".mcpjvm/sample/run-state.sqlite"))).isTrue();
        }
    }

    private JsonNode normalizeResult(JsonNode result) {
        ObjectNode normalized = result.deepCopy();
        JsonNode details = normalized.path("details");
        if (details.isObject() && details.has("configFileAbs")) {
            ((ObjectNode) details).put("configFileAbs", "<workspace>/.mcpjvm/probe-config.json");
        }
        return normalized;
    }

    private static boolean mutating(ArtifactManagementAction action) {
        return action == ArtifactManagementAction.PROBE_CONFIG_UPSERT
                || action == ArtifactManagementAction.PROBE_CONFIG_RELOAD
                || action == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT;
    }

    private ObjectNode validSchemaInput(ArtifactManagementAction action) {
        ObjectNode input = mapper.createObjectNode();
        if (action == ArtifactManagementAction.PROBE_CONFIG_UPSERT) {
            input.set("payload", validProbe());
        } else if (action == ArtifactManagementAction.PROJECT_CONTEXT_UPSERT) {
            input.put("projectName", "sample").set("payload", validProject());
        }
        return input;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> values = new java.util.LinkedHashSet<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static Set<String> textValues(JsonNode array) {
        Set<String> values = new java.util.LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    record SchemaCase(
            ArtifactManagementAction action, Set<String> properties, Set<String> required) {
        @Override
        public String toString() {
            return action.name();
        }
    }

    record Harness(OperationDirectory directory, DefaultArtifactManagementFeature legacy) { }

    private final class TestRegistrySource implements ProbeRegistryReloader, ProbeRegistryProvider {
        private ProbeRegistry active;

        @Override
        public ProbeRegistry reload() {
            reloads.incrementAndGet();
            active = new ProbeRegistry(List.of(new ProbeRegistration(
                    "local", "http://127.0.0.1:9191")));
            return active;
        }

        @Override
        public ProbeRegistry current() {
            return active;
        }
    }

    private static String operationId(ArtifactManagementAction action) {
        return "artifact_management." + action.artifactType().value() + "." + action.action().value();
    }
}
