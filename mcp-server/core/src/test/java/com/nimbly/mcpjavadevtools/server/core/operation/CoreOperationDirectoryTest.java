package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.DefaultExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.DefaultFailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.DefaultRouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.DefaultPerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.PerformanceSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.DefaultRegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.DefaultSecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.SecuritySuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.operation.ArtifactOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation.ProbeOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.operation.TransportExecuteArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.operation.TransportExecutionOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectoryOwners;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationAlias;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationArgumentDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaRules;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;

/** Acceptance coverage for the production 610 operation aggregate. */
class CoreOperationDirectoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger PROBE_CALLS = new AtomicInteger();
    private static final AtomicInteger JVM_CALLS = new AtomicInteger();

    @Test
    void assemblesAllApprovedOperationsFromRealCoreOwners() {
        CoreOperationDirectory aggregate = aggregate();
        OperationDirectory directory = aggregate.directory();

        assertThat(directory.manifest().registrations())
                .hasSize(CoreOperationDirectory.EXPECTED_OPERATION_COUNT)
                .extracting(registration -> registration.descriptor().operationId())
                .doesNotHaveDuplicates();
        assertThat(directory.manifest().registrations())
                .extracting(OperationRegistration::operationCatalog)
                .containsOnly(CoreOperationDirectory.class.getName());
        assertThat(directory.manifest().descriptors())
                .allSatisfy(descriptor -> assertThat(descriptor.documentation().examples()).isNotEmpty());

        assertThat(directory.traceInventory()).hasSize(54)
                .extracting(OperationTraceEntry::operationId)
                .doesNotHaveDuplicates();
        assertThat(directory.traceInventory())
                .extracting(OperationTraceEntry::operationCatalog)
                .containsOnly(CoreOperationDirectory.class.getName());
        assertThat(directory.traceInventory()).allSatisfy(entry ->
                assertThat(entry.compatibility()).containsKeys(
                        "provenanceKind", "normalization", "resultComparison", "parityScenario"));
        assertThat(directory.traceInventory().stream()
                .filter(entry -> "RELEASED_LEGACY_INVOCATION".equals(
                        entry.compatibility().get("provenanceKind"))))
                .hasSize(50)
                .allSatisfy(entry -> assertThat(entry.compatibility())
                        .containsKeys("releasedTool", "releasedAction", "actionless"));
        assertThat(directory.traceInventory().stream()
                .filter(entry -> "NEW_DIRECT_CDE_OPERATION".equals(
                        entry.compatibility().get("provenanceKind"))))
                .hasSize(4)
                .allSatisfy(entry -> assertThat(entry.compatibility())
                        .containsEntry("provenanceTool", "execution_orchestration")
                        .containsEntry("provenanceAction", "execute")
                        .doesNotContainKeys("releasedTool", "releasedAction", "actionless"));
        assertThat(directory.traceInventory().stream()
                .filter(entry -> "true".equals(entry.compatibility().get("actionless")))
                .map(OperationTraceEntry::operationId)
                .toList())
                .containsExactlyInAnyOrder("execution_profile_export.export", "transport_execute.execute");
        assertThat(directory.manifest().resolveAlias(
                new OperationAlias("execution_profile_export", "", true)))
                .isEqualTo(OperationId.of("execution_profile_export.export"));
        assertThat(directory.manifest().resolveAlias(
                new OperationAlias("transport_execute", "", true)))
                .isEqualTo(OperationId.of("transport_execute.execute"));
        assertThat(aggregate.manifest()).isSameAs(directory.manifest());
        assertThat(aggregate.traceInventory()).isEqualTo(directory.traceInventory());
    }

    @Test
    void generatedManifestAndTraceAgreeForEveryOperationProvenance() {
        CoreOperationDirectory aggregate = aggregate();
        Map<String, OperationTraceEntry> traces = aggregate.traceInventory().stream()
                .collect(java.util.stream.Collectors.toMap(
                        OperationTraceEntry::operationId, entry -> entry));

        assertThat(traces).hasSize(CoreOperationDirectory.EXPECTED_OPERATION_COUNT);
        for (OperationRegistration<?, ?> registration : aggregate.manifest().registrations()) {
            OperationDescriptor descriptor = registration.descriptor();
            OperationTraceEntry trace = traces.get(descriptor.operationId().value());
            assertThat(trace).as(descriptor.operationId().value()).isNotNull();
            assertThat(trace.actionless()).isEqualTo(registration.provenance().actionless());
            assertThat(trace.action()).isEqualTo(registration.provenance().invocationAction());
            assertThat(trace.compatibility()).containsAllEntriesOf(registration.compatibility());
        }
    }

    @Test
    void validatesEveryRealManifestExampleAgainstItsExplicitSchema() {
        OperationManifest manifest = aggregate().directory().manifest();

        assertThat(manifest.registrations()).allSatisfy(registration -> {
            OperationDescriptor descriptor = registration.descriptor();
            for (var example : descriptor.documentation().examples()) {
                assertThat(OperationSchemaValidator.violations(registration.inputSchema(), example))
                        .as(descriptor.operationId().value())
                        .isEmpty();
            }
            assertThat(descriptor.documentation().arguments())
                    .extracting(OperationArgumentDocumentation::name)
                    .containsExactlyInAnyOrderElementsOf(
                            registration.inputSchema().definition().path("properties").propertyStream()
                                    .map(entry -> entry.getKey()).toList());
        });
        assertThat(manifest.registrations())
                .extracting(registration -> registration.descriptor().requestType())
                .contains(ArtifactOperationArguments.class.getName(),
                        ProbeOperationArguments.class.getName(),
                        "com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleInput",
                        TransportExecuteArguments.class.getName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approvedOperationIds")
    void bindsEveryApprovedOperationToOneTypedOwnerAndTwoSchemas(OperationId operationId) {
        OperationDirectory directory = aggregate().directory();
        OperationRegistration<?, ?> registration = directory.manifest().registration(operationId);

        assertThat(registration).isNotNull();
        assertThat(registration.requestType().getName())
                .isEqualTo(registration.descriptor().requestType());
        assertThat(registration.resultType().getName())
                .isEqualTo(registration.descriptor().resultType());
        assertThat(registration.descriptor().executableOwner()).isNotBlank();
        assertThat(registration.inputSchema().definition().path("$schema").asText())
                .isEqualTo(OperationSchemaRules.DRAFT_2020_12);
        assertThat(registration.resultSchema().definition().path("$schema").asText())
                .isEqualTo(OperationSchemaRules.DRAFT_2020_12);
        assertThat(registration.inputSchema().definition().path("additionalProperties").isBoolean())
                .isTrue();
        assertThat(registration.resultSchema().definition().path("additionalProperties").isBoolean())
                .isTrue();
        assertThat(registration.compatibility()).containsEntry("operationId", operationId.value());
    }

    @Test
    void executesHeterogeneousBindingsThroughTheProductionAggregate() {
        PROBE_CALLS.set(0);
        JVM_CALLS.set(0);
        OperationDirectory directory = aggregate().directory();
        JsonNode probeInput = JSON.createObjectNode().put("baseUrl", "http://127.0.0.1:9191");
        OperationRegistration<?, ?> probeRegistration = directory.manifest().registration(
                OperationId.of("probe.check"));
        JsonNode rawProbeResult = probeRegistration.execute(probeInput);
        assertThat(OperationSchemaValidator.violations(probeRegistration.resultSchema(), rawProbeResult))
                .isEmpty();
        PROBE_CALLS.set(0);

        OperationExecutionResult probe = directory.execute(
                OperationId.of("probe.check"), probeInput);
        OperationExecutionResult jvm = directory.execute(
                OperationId.of("jvm_lifecycle.list_jvms"), JSON.createObjectNode());

        assertThat(probe.status()).as("%s: %s (%s)", probe.reasonCode(), probe.reason(), probe.result())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(jvm.status()).as("%s: %s (%s)", jvm.reasonCode(), jvm.reason(), jvm.result())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(PROBE_CALLS).hasValue(1);
        assertThat(JVM_CALLS).hasValue(1);
    }

    @Test
    void preservesCanonicalArgumentsDefaultsOpenValuesAndActionlessIdentity() throws Exception {
        OperationManifest manifest = aggregate().directory().manifest();
        OperationRegistration<?, ?> artifact = manifest.registration(
                OperationId.of("artifact_management.probe_config.read"));
        OperationRegistration<?, ?> attach = manifest.registration(OperationId.of("jvm_lifecycle.attach"));
        OperationRegistration<?, ?> transport = manifest.registration(
                OperationId.of("transport_execute.execute"));

        assertThat(artifact.inputSchema().definition().path("properties").has("artifactType"))
                .isFalse();
        assertThat(artifact.inputSchema().definition().path("properties").has("action"))
                .isFalse();
        assertThat(artifact.provenance().discriminators())
                .containsEntry("artifactType", "probe_config")
                .containsEntry("action", "read");

        JsonNode attachProperties = attach.inputSchema().definition().path("properties");
        assertThat(attachProperties.path("probeHost").path("default").asText())
                .isEqualTo("127.0.0.1");
        assertThat(attachProperties.path("probePort").path("default").asInt())
                .isEqualTo(9191);
        assertThat(OperationSchemaValidator.violations(attach.inputSchema(), JSON.readTree(
                "{\"pid\":\"123\",\"expectedProcessStartEpochMs\":1,\"confirm\":false}")))
                .isNotEmpty();

        JsonNode transportInput = JSON.readTree(
                "{\"protocol\":\"http\",\"request\":{\"body\":null}}");
        assertThat(OperationSchemaValidator.violations(transport.inputSchema(), transportInput))
                .isEmpty();
        TransportExecuteArguments decoded = (TransportExecuteArguments) transport.decoder().decode(transportInput);
        assertThat(decoded.request()).containsEntry("body", null);
        assertThat(transport.inputSchema().definition().path("properties").path("options")
                .path("default").path("wrappedOnly").asBoolean()).isTrue();

        OperationTraceEntry actionless = manifest.traceInventory().stream()
                .filter(entry -> entry.operationId().equals("transport_execute.execute"))
                .findFirst().orElseThrow();
        assertThat(actionless.actionless()).isTrue();
        assertThat(actionless.action()).isEmpty();
        assertThat(actionless.compatibility()).containsEntry("actionless", "true");
    }

    static Stream<OperationId> approvedOperationIds() {
        return aggregate().manifest().registrations().stream()
                .map(registration -> registration.descriptor().operationId());
    }

    @Test
    void rejectsMissingDuplicateAndOrphanAggregateOwners() {
        List<OperationRegistration<?, ?>> registrations = aggregate().directory().manifest().registrations();
        OperationManifestDocument document = OperationManifestLoader.loadBuiltIn();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(
                        registrations.subList(0, registrations.size() - 1), document))
                .withMessageContaining("orphan operation documentation");

        List<OperationRegistration<?, ?>> duplicate = new java.util.ArrayList<>(registrations);
        duplicate.add(registrations.getFirst());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(duplicate, document))
                .withMessageContaining("duplicate executable operation");

        List<OperationRegistration<?, ?>> orphan = new java.util.ArrayList<>(registrations);
        orphan.add(orphanOwner());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(orphan, document))
                .withMessageContaining("missing operation documentation: orphan.owner");

        Map<OperationId, OperationDocumentation> orphanDocumentation =
                new LinkedHashMap<>(document.operations());
        orphanDocumentation.put(OperationId.of("orphan.owner"),
                OperationDocumentation.legacy(OperationId.of("orphan.owner"), "none"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(registrations,
                        new OperationManifestDocument(document.version(), orphanDocumentation)))
                .withMessageContaining("orphan operation documentation: orphan.owner");
    }

    private static CoreOperationDirectory aggregate() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                Optional::empty, new ArtifactJsonStore(JSON), new SqliteRunStateStore(JSON), JSON);
        ArtifactOperationCatalog artifact = new ArtifactOperationCatalog(
                new ProbeConfigOperations(support), new ProjectContextOperations(support),
                new PlanOperations(support), new RunResultOperations(support),
                new ExecutionExportOperations(support),
                new OperationExposure(ArtifactOperationCatalog.TOOL_NAME, "aggregate-test",
                        Arrays.stream(ArtifactManagementAction.values())
                                .map(ArtifactManagementAction::routeId).toList()));
        ExecutionExportArtifactGateway gateway = request -> ArtifactManagementResult.success(
                ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE, Map.of());
        ExportExecutionProfileOperation exportOwner = new ExportExecutionProfileOperation(
                gateway, new ExecutionProfileExportArtifactInputMapper(JSON),
                new OperationTraceMetadata(
                        "aggregate-test", "aggregate-test", "aggregate-test", "aggregate-test",
                        "aggregate-test", "filesystem_export", Map.of("owner", "aggregate-test")));
        ExecutionProfileExportOperationCatalog export = new ExecutionProfileExportOperationCatalog(
                exportOwner, new OperationExposure(
                        ExecutionProfileExportOperationCatalog.TOOL_NAME, "aggregate-test", List.of("export")));
        return new CoreOperationDirectory(
                new CoreOperationDirectoryOwners(
                        artifact,
                        new JvmLifecycleOperationCatalog(jvmHandlers()),
                        new ProbeOperationCatalog(probeHandlers()),
                        export,
                        new DefaultRouteSynthesisFeature(routeHandlers()),
                        new DefaultFailureAnalysisFeature(failureHandlers()),
                        new DefaultTransportExecutionFeature(transportHandlers()),
                        new DefaultExecutionOrchestrationFeature(orchestrationHandlers()),
                        new DefaultRegressionSuiteFeature(regressionHandlers()),
                        new DefaultPerformanceSuiteFeature(performanceHandlers()),
                        new DefaultSecuritySuiteFeature(securityHandlers())),
                JSON);
    }

    private static List<ProbeActionHandler> probeHandlers() {
        return Arrays.stream(ProbeAction.values()).map(CoreOperationDirectoryTest::probeHandler).toList();
    }

    private static ProbeActionHandler probeHandler(ProbeAction action) {
        return new ProbeActionHandler() {
            @Override
            public ProbeAction action() { return action; }

            @Override
            public ProbeResult execute(ProbeRequest request) {
                PROBE_CALLS.incrementAndGet();
                return ProbeResult.success();
            }
        };
    }

    private static List<JvmLifecycleActionHandler> jvmHandlers() {
        return Arrays.stream(JvmLifecycleAction.values())
                .map(CoreOperationDirectoryTest::jvmHandler).toList();
    }

    private static JvmLifecycleActionHandler jvmHandler(JvmLifecycleAction action) {
        return new JvmLifecycleActionHandler() {
            @Override
            public JvmLifecycleAction action() { return action; }

            @Override
            public JvmLifecycleResult execute(JvmLifecycleRequest request) {
                JVM_CALLS.incrementAndGet();
                return JvmLifecycleResult.blocked(action.value() + "_aggregate_test");
            }
        };
    }

    private static List<RouteSynthesisActionHandler> routeHandlers() {
        return Arrays.stream(RouteSynthesisAction.values())
                .map(CoreOperationDirectoryTest::routeHandler).toList();
    }

    private static RouteSynthesisActionHandler routeHandler(RouteSynthesisAction action) {
        return new RouteSynthesisActionHandler() {
            @Override
            public RouteSynthesisAction action() { return action; }

            @Override
            public RouteSynthesisResult execute(RouteSynthesisRequest request) { return null; }
        };
    }

    private static List<FailureAnalysisActionHandler> failureHandlers() {
        return Arrays.stream(FailureAnalysisAction.values())
                .map(CoreOperationDirectoryTest::failureHandler).toList();
    }

    private static FailureAnalysisActionHandler failureHandler(FailureAnalysisAction action) {
        return new FailureAnalysisActionHandler() {
            @Override
            public FailureAnalysisAction action() { return action; }

            @Override
            public FailureAnalysisResult execute(FailureAnalysisRequest request) { return null; }
        };
    }

    private static List<TransportExecutionActionHandler> transportHandlers() {
        return Arrays.stream(TransportExecutionAction.values())
                .map(CoreOperationDirectoryTest::transportHandler).toList();
    }

    private static TransportExecutionActionHandler transportHandler(TransportExecutionAction action) {
        return new TransportExecutionActionHandler() {
            @Override
            public TransportExecutionAction action() { return action; }

            @Override
            public ExecuteTransportResult execute(TransportExecutionRequest request) { return null; }
        };
    }

    private static List<ExecutionOrchestrationActionHandler> orchestrationHandlers() {
        return Arrays.stream(ExecutionOrchestrationAction.values())
                .map(CoreOperationDirectoryTest::orchestrationHandler).toList();
    }

    private static ExecutionOrchestrationActionHandler orchestrationHandler(
            ExecutionOrchestrationAction action) {
        return new ExecutionOrchestrationActionHandler() {
            @Override
            public ExecutionOrchestrationAction action() { return action; }

            @Override
            public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
                return null;
            }
        };
    }

    private static List<RegressionSuiteActionHandler> regressionHandlers() {
        return Arrays.stream(RegressionSuiteAction.values())
                .map(CoreOperationDirectoryTest::regressionHandler).toList();
    }

    private static RegressionSuiteActionHandler regressionHandler(RegressionSuiteAction action) {
        return new RegressionSuiteActionHandler() {
            @Override
            public RegressionSuiteAction action() { return action; }

            @Override
            public RegressionSuiteResult execute(RegressionSuiteRequest request) { return null; }
        };
    }

    private static List<PerformanceSuiteActionHandler> performanceHandlers() {
        return Arrays.stream(PerformanceSuiteAction.values())
                .map(CoreOperationDirectoryTest::performanceHandler).toList();
    }

    private static PerformanceSuiteActionHandler performanceHandler(PerformanceSuiteAction action) {
        return new PerformanceSuiteActionHandler() {
            @Override
            public PerformanceSuiteAction action() { return action; }

            @Override
            public PerformanceSuiteResult execute(PerformanceSuiteRequest request) { return null; }
        };
    }

    private static List<SecuritySuiteActionHandler> securityHandlers() {
        return Arrays.stream(SecuritySuiteAction.values())
                .map(CoreOperationDirectoryTest::securityHandler).toList();
    }

    private static SecuritySuiteActionHandler securityHandler(SecuritySuiteAction action) {
        return new SecuritySuiteActionHandler() {
            @Override
            public SecuritySuiteAction action() { return action; }

            @Override
            public SecuritySuiteResult execute(SecuritySuiteRequest request) { return null; }
        };
    }

    private static OperationRegistration<String, String> orphanOwner() {
        OperationDescriptor descriptor = new OperationDescriptor(
                "orphan", "owner", String.class.getName(), String.class.getName(),
                CoreOperationDirectoryTest.class.getName(),
                new OperationTraceMetadata(
                        "orphan-test", "orphan-test", "orphan-test", "orphan-test",
                        "orphan-test", "none", Map.of("owner", "orphan-test")));
        return new OperationRegistration<>(
                descriptor, String.class, String.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(),
                        OperationSafetyPolicy.legacy("none")),
                OperationRequestDecoders.typed(JSON, String.class), input -> input,
                OperationResultEncoders.typed(JSON, String.class));
    }
}
