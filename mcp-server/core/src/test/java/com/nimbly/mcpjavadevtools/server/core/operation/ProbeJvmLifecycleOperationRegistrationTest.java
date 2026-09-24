package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.attach.impl.AttachAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.deactivate.impl.DeactivateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.listjvms.impl.ListJvmsAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.DefaultJvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.JvmLifecycleExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.ProbeHostPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl.ProbeActuateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl.ProbeCaptureAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl.ProbeCheckAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.LocalProbeProfilerOutputStore;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl.ProbeProfilerAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl.ProbeResetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl.ProbeWaitForHitAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.HttpProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable Core-directory contract evidence for MCPJVM-620's ten rows. */
class ProbeJvmLifecycleOperationRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Set<String> OWNED_IDS = Set.of(
            "probe.actuate", "probe.capture", "probe.check", "probe.profiler",
            "probe.reset", "probe.status", "probe.wait_for_hit",
            "jvm_lifecycle.attach", "jvm_lifecycle.deactivate", "jvm_lifecycle.list_jvms");

    private Map<String, OperationRegistration<?, ?>> registrations;
    private OperationDirectory directory;

    @BeforeEach
    void setUp() {
        List<ProbeActionHandler> probeHandlers = Arrays.stream(ProbeAction.values())
                .map(CapturingProbeHandler::new).map(ProbeActionHandler.class::cast).toList();
        List<JvmLifecycleActionHandler> jvmHandlers = Arrays.stream(JvmLifecycleAction.values())
                .map(CapturingJvmHandler::new).map(JvmLifecycleActionHandler.class::cast).toList();
        List<OperationRegistration<?, ?>> owned = Stream.concat(
                        ProbeOperationRegistrations.create(
                                new ProbeOperationCatalog(probeHandlers), JSON).stream(),
                        JvmLifecycleOperationRegistrations.create(
                                new JvmLifecycleOperationCatalog(jvmHandlers), JSON).stream())
                .toList();
        registrations = owned.stream().collect(java.util.stream.Collectors.toMap(
                value -> value.descriptor().operationId().value(), value -> value,
                (left, right) -> left, LinkedHashMap::new));
        directory = new OperationDirectory(owned, ownedDocument(), JSON);
    }

    @ParameterizedTest(name = "registration {0}")
    @MethodSource("ownedIds")
    void registersExactBoundedOwnerSchemaIdentityAndCancellation(String operationId) {
        OperationRegistration<?, ?> registration = registrations.get(operationId);

        assertThat(registration).isNotNull();
        assertThat(registration.descriptor().executableOwner()).contains("Capturing");
        assertThat(registration.decoder()).isInstanceOf(BoundedOperationRequestDecoder.class);
        assertThat(registration.encoder()).isInstanceOf(BoundedOperationResultEncoder.class);
        assertThat(registration.inputSchema().definition().path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(registration.inputSchema().definition().path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(registration.provenance().invocationTool())
                .isEqualTo(operationId.substring(0, operationId.indexOf('.')));
        assertThat(registration.provenance().invocationAction())
                .isEqualTo(operationId.substring(operationId.indexOf('.') + 1));
        assertThat(registration.provenance().parityScenario())
                .isEqualTo(operationId.replace('.', '_'));
        assertThat(OperationCancellationSupport.state(
                registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION);
        assertThat(registration.safety().cancellationSupported()).isTrue();
    }

    @ParameterizedTest(name = "execute {0}")
    @MethodSource("ownedIds")
    void routesEveryCanonicalInputThroughItsExactTypedOwner(String operationId) {
        OperationExecutionResult result = substantiveDirectory().execute(new OperationInvocation(
                OperationId.of(operationId), input(operationId), mutating(operationId)));

        assertThat(result.status()).as("%s: %s (%s)",
                        result.reasonCode(), result.reason(), result.result())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("reasonCode").asText()).as(operationId)
                .isIn("SUCCESS", "success", "active", "deactivated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesSchemaValidExplicitEmptyProbeHttpOptions() throws Exception {
        BoundedOperationRequestDecoder<Object> decoder =
                (BoundedOperationRequestDecoder<Object>) registrations.get("probe.check").decoder();
        for (String json : List.of("{\"http\":{}}", "{\"http\":{\"headers\":{}}}")) {
            JsonNode input = read(json);
            ByteArrayOutputStream canonical = new ByteArrayOutputStream();
            decoder.write(decoder.decode(input), canonical);

            assertThat(JSON.readTree(canonical.toByteArray())).as(json).isEqualTo(input);
        }
    }

    @ParameterizedTest(name = "unknown field {0}")
    @MethodSource("ownedIds")
    void rejectsUnknownCanonicalFieldsForEveryOwnedRow(String operationId) {
        ObjectNode invalid = input(operationId).deepCopy();
        invalid.put("unknown", true);

        OperationExecutionResult result = directory.execute(new OperationInvocation(
                OperationId.of(operationId), invalid, mutating(operationId)));

        assertThat(result.status()).as(operationId).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("operation_input_schema_invalid");
    }

    @Test
    void enforcesStrictLineKeyAndMutationConfirmationContracts() {
        assertThat(OperationSchemaValidator.violations(
                registrations.get("probe.wait_for_hit").inputSchema(),
                JSON.createObjectNode().put("key", "com.example.Sample#run:42")))
                .isEmpty();
        OperationExecutionResult missingKey = directory.execute(new OperationInvocation(
                OperationId.of("probe.wait_for_hit"), JSON.createObjectNode(), false));
        OperationExecutionResult unconfirmedAttach = directory.execute(new OperationInvocation(
                OperationId.of("jvm_lifecycle.attach"), input("jvm_lifecycle.attach"), false));

        assertThat(missingKey.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(unconfirmedAttach.status()).isEqualTo(OperationExecutionStatus.CONFIRMATION_REQUIRED);
    }

    @ParameterizedTest(name = "timeout and cooperative stop {0}")
    @MethodSource("ownedIds")
    void deadlineInterruptsEveryBoundedDelegate(String operationId) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        OperationDirectory bounded = blockingDirectory(operationId, started, interrupted, 100);

        OperationExecutionResult result = bounded.execute(new OperationInvocation(
                OperationId.of(operationId), input(operationId), mutating(operationId)));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest(name = "caller interruption contains substantive mutation {0}")
    @MethodSource("mutatingIds")
    void callerInterruptionContainsEverySubstantiveMutation(String operationId) throws Exception {
        try (SubstantiveCancellationFixture fixture =
                substantiveCancellationFixture(operationId, 60_000)) {
            AtomicReference<OperationExecutionResult> result = new AtomicReference<>();
            Thread caller = new Thread(() -> result.set(fixture.directory().execute(
                    new OperationInvocation(OperationId.of(operationId), input(operationId), true))));

            caller.start();
            assertThat(fixture.state().started.await(1, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
            assertThat(result.get().reasonCode()).isEqualTo("operation_caller_interrupted");
            assertContainedMutation(fixture);
        }
    }

    @ParameterizedTest(name = "deadline contains substantive mutation {0}")
    @MethodSource("mutatingIds")
    void deadlineInterruptsAndContainsEverySubstantiveMutation(String operationId) throws Exception {
        try (SubstantiveCancellationFixture fixture = substantiveCancellationFixture(
                operationId, operationId.startsWith("jvm_lifecycle.") ? 3_000 : 100)) {
            OperationExecutionResult result = fixture.directory().execute(new OperationInvocation(
                    OperationId.of(operationId), input(operationId), true));

            assertThat(fixture.state().started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
            assertThat(result.reasonCode()).isEqualTo("operation_timeout");
            assertContainedMutation(fixture);
        }
    }

    @Test
    void generatesTenRowManifestAndTraceEvidence() throws Exception {
        OperationDirectory substantive = substantiveDirectory();
        assertThat(substantive.manifest().descriptors()).hasSize(10);
        assertThat(substantive.manifest().descriptors())
                .extracting(descriptor -> descriptor.executableOwner())
                .containsExactlyInAnyOrderElementsOf(substantiveOwnerNames());
        assertThat(substantive.traceInventory()).hasSize(10).allSatisfy(entry ->
                assertThat(entry.compatibility()).containsKeys(
                        "normalization", "resultComparison", "parityScenario", "cancellationState"));
        Map<String, Object> routing = routingInventory(substantive);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) routing.get("operationMappings");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) routing.get("routingFiles");
        assertThat(mappings).hasSize(10).allSatisfy(row -> {
            assertThat(row.get("before")).isInstanceOf(Map.class);
            assertThat(row.get("after")).isInstanceOf(Map.class);
        });
        assertThat(files).hasSize(24).allSatisfy(row ->
                assertThat(routingPath((String) row.get("path"))).isRegularFile());
        assertThat(files).filteredOn(row ->
                "COMPATIBILITY_RETAINED_UNTIL_611".equals(row.get("classification")))
                .hasSize(2).allSatisfy(row -> {
                    assertThat(row.get("caller")).isNotNull();
                    assertThat(row.get("deletionCondition")).asString().contains("#611");
                });
        Path evidence = Path.of("target", "mcpjvm-620-evidence");
        Files.createDirectories(evidence);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest.json").toFile(), substantive.manifest().descriptors());
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-inventory.json").toFile(), substantive.traceInventory());
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("routing-inventory.json").toFile(), routing);
    }

    static Stream<String> ownedIds() {
        return OWNED_IDS.stream().sorted();
    }

    static Stream<String> mutatingIds() {
        return OWNED_IDS.stream().filter(ProbeJvmLifecycleOperationRegistrationTest::mutating).sorted();
    }

    private OperationManifestDocument ownedDocument() {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        Map<OperationId, com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation>
                documentation = new LinkedHashMap<>();
        all.operations().forEach((id, value) -> {
            if (OWNED_IDS.contains(id.value())) {
                documentation.put(id, value);
            }
        });
        return new OperationManifestDocument(all.version(), documentation);
    }

    private OperationDirectory substantiveDirectory() {
        List<OperationRegistration<?, ?>> owned = Stream.concat(
                ProbeOperationRegistrations.create(
                        new ProbeOperationCatalog(substantiveProbeHandlers()), JSON).stream(),
                JvmLifecycleOperationRegistrations.create(
                        new JvmLifecycleOperationCatalog(substantiveJvmHandlers()), JSON).stream())
                .toList();
        return new OperationDirectory(owned, ownedDocument(), JSON);
    }

    private static List<ProbeActionHandler> substantiveProbeHandlers() {
        return substantiveProbeHandlers(ProbeJvmLifecycleOperationRegistrationTest::substantiveResponse);
    }

    private static List<ProbeActionHandler> substantiveProbeHandlers(ProbeEndpointClient client) {
        return substantiveProbeHandlers(client, endpointConfiguration());
    }

    private static List<ProbeActionHandler> substantiveProbeHandlers(
            ProbeEndpointClient client, ProbeEndpointConfiguration endpoint) {
        ProbeTargetResolver resolver = new ProbeTargetResolver(endpoint, null);
        ProbeResponseCompactionPolicy compaction =
                new ProbeResponseCompactionPolicy(false, 64, 4, 8, 64, Set.of("content-type"));
        ProbeStatusAction status = new ProbeStatusAction(resolver, endpoint, client, compaction);
        return List.of(
                new ProbeCheckAction(resolver, endpoint, client, compaction),
                status,
                new ProbeResetAction(resolver, endpoint, client, compaction),
                new ProbeWaitForHitAction(status, endpoint.requestPolicy(), Clock.systemUTC(), Thread::sleep),
                new ProbeCaptureAction(resolver, endpoint, client, compaction),
                new ProbeActuateAction(resolver, endpoint, client, compaction),
                new ProbeProfilerAction(
                        resolver, endpoint, client, compaction, new LocalProbeProfilerOutputStore()));
    }

    private static List<JvmLifecycleActionHandler> substantiveJvmHandlers() {
        JvmCandidate candidate = new JvmCandidate(
                "1234", "fixture", "test", "plain_java", List.of("test"), 1L);
        JvmLifecycleHelper helper = request -> switch (request.operation()) {
            case "discover" -> new JvmLifecycleHelperResult(
                    "discover", "ok", "success", List.of("1234"), List.of(candidate), List.of());
            case "attach" -> new JvmLifecycleHelperResult(
                    "attach", "active", "active", List.of(), List.of(), List.of());
            case "deactivate" -> new JvmLifecycleHelperResult(
                    "deactivate", "deactivated", "deactivated", List.of(), List.of(), List.of());
            default -> JvmLifecycleHelperResult.failure(request.operation(), "unexpected_operation");
        };
        return substantiveJvmHandlers(helper);
    }

    private static List<JvmLifecycleActionHandler> substantiveJvmHandlers(JvmLifecycleHelper helper) {
        JvmLifecycleArtifactResolver artifacts =
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("fixture-agent.jar"));
        return List.of(
                new ListJvmsAction(helper),
                new AttachAction(helper, artifacts, new ProbeHostPolicy(Set.of())),
                new DeactivateAction(helper, artifacts));
    }

    private static List<String> substantiveOwnerNames() {
        return List.of(
                ProbeCheckAction.class.getName(), ProbeStatusAction.class.getName(),
                ProbeResetAction.class.getName(), ProbeWaitForHitAction.class.getName(),
                ProbeCaptureAction.class.getName(), ProbeActuateAction.class.getName(),
                ProbeProfilerAction.class.getName(), ListJvmsAction.class.getName(),
                AttachAction.class.getName(), DeactivateAction.class.getName());
    }

    private static Map<String, Object> routingInventory(OperationDirectory substantive) {
        List<Map<String, Object>> mappings = substantive.manifest().descriptors().stream().map(descriptor -> {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("tool", descriptor.toolName());
            before.put("action", descriptor.action());
            before.put("executableOwner", descriptor.executableOwner());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("operationId", descriptor.operationId().value());
            after.put("registration", descriptor.trace().requestMapper());
            after.put("executableOwner", descriptor.executableOwner());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operationId", descriptor.operationId().value());
            row.put("before", before);
            row.put("after", after);
            return row;
        }).toList();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("operationMappings", mappings);
        inventory.put("routingFiles", Stream.of(
                probeRoutingFiles(), jvmRoutingFiles(), compatibilityRoutingFiles())
                .flatMap(List::stream).toList());
        inventory.put("addedRoutingFiles", List.of());
        inventory.put("removedRoutingFiles", List.of());
        inventory.put("modifiedRoutingFiles", List.of(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                        + "probe/operation/ProbeOperationRegistrations.java",
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                        + "jvmlifecycle/operation/JvmLifecycleOperationRegistrations.java"));
        inventory.put("absentRoutingCategories", Map.of(
                "HANDLER_ALIAS", "none; substantive owners implement the shared handler contracts directly",
                "STANDALONE_DISPATCHER", "none; each capability catalog owns its closed action dispatch"));
        inventory.put("unclassifiedRoutingFiles", List.of());
        return inventory;
    }

    private static List<Map<String, Object>> probeRoutingFiles() {
        String root = "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/probe/";
        String catalog = root + "operation/ProbeOperationCatalog.java";
        return List.of(
                routingFile(root + "action/check/impl/ProbeCheckAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/status/impl/ProbeStatusAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/reset/impl/ProbeResetAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/waitforhit/impl/ProbeWaitForHitAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/capture/impl/ProbeCaptureAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/actuate/impl/ProbeActuateAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/profiler/impl/ProbeProfilerAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/ProbeActionHandler.java", "ACTION_HANDLER_CONTRACT", catalog),
                routingFile(root + "operation/ProbeOperation.java", "OPERATION_WRAPPER", catalog),
                routingFile(catalog, "CAPABILITY_CATALOG_DISPATCHER",
                        root + "operation/ProbeOperationRegistrations.java"));
    }

    private static List<Map<String, Object>> jvmRoutingFiles() {
        String root = "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/jvmlifecycle/";
        String catalog = root + "operation/JvmLifecycleOperationCatalog.java";
        return List.of(
                routingFile(root + "action/listjvms/impl/ListJvmsAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/attach/impl/AttachAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/deactivate/impl/DeactivateAction.java", "ACTION_IMPLEMENTATION", catalog),
                routingFile(root + "action/JvmLifecycleActionHandler.java", "ACTION_HANDLER_CONTRACT", catalog),
                routingFile(root + "operation/JvmLifecycleOperation.java", "OPERATION_WRAPPER", catalog),
                routingFile(catalog, "CAPABILITY_CATALOG_DISPATCHER",
                        root + "operation/JvmLifecycleOperationRegistrations.java"));
    }

    private static List<Map<String, Object>> compatibilityRoutingFiles() {
        String core = "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/";
        String app = "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/";
        return List.of(
                routingFile(core + "probe/ProbeFeature.java", "CORE_FEATURE_BOUNDARY", "multiple Core/Application callers"),
                routingFile(core + "probe/DefaultProbeFeature.java", "DEFAULT_FEATURE_ROUTER", app + "configuration/ProbeConfiguration.java"),
                routingFile(core + "probe/operation/ProbeOperationRegistrations.java", "CDE_REGISTRATION",
                        "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation/composition/"
                                + "CoreOperationDirectory.java"),
                routingFile(core + "jvmlifecycle/JvmLifecycleFeature.java", "CORE_FEATURE_BOUNDARY", "multiple Core/Application callers"),
                routingFile(core + "jvmlifecycle/DefaultJvmLifecycleFeature.java", "DEFAULT_FEATURE_ROUTER", app + "configuration/JvmLifecycleConfiguration.java"),
                routingFile(core + "jvmlifecycle/operation/JvmLifecycleOperationRegistrations.java", "CDE_REGISTRATION",
                        "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation/composition/"
                                + "CoreOperationDirectory.java"),
                compatibilityFile(app + "mcp/tools/probe/ProbeMcpTool.java", app + "configuration/ProbeConfiguration.java"),
                compatibilityFile(app + "mcp/tools/jvmlifecycle/JvmLifecycleMcpTool.java", app + "configuration/JvmLifecycleConfiguration.java"));
    }

    private static Map<String, Object> routingFile(String path, String category, String caller) {
        return Map.of("path", path, "category", category, "before", "PRESENT", "after", "RETAINED",
                "classification", "RETAINED", "caller", caller);
    }

    private static Map<String, Object> compatibilityFile(String path, String caller) {
        return Map.of("path", path, "category", "LEGACY_APPLICATION_ADAPTER", "before", "PRESENT",
                "after", "RETAINED", "classification", "COMPATIBILITY_RETAINED_UNTIL_611",
                "caller", caller, "deletionCondition",
                "#611 approves public CDE cutover, raw-STDIO parity passes, and removes this legacy Tool registration");
    }

    private static Path routingPath(String repositoryPath) {
        return Path.of("..").resolve(repositoryPath.substring("mcp-server/".length())).normalize();
    }

    private static ProbeEndpointConfiguration endpointConfiguration() {
        return endpointConfiguration("http://127.0.0.1:9191");
    }

    private static ProbeEndpointConfiguration endpointConfiguration(String baseUrl) {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMillis(100),
                Duration.ofSeconds(5), 1, 10);
        return new ProbeEndpointConfiguration(
                baseUrl,
                new ProbeEndpointPaths(
                        "/__probe/status", "/__probe/reset", "/__probe/actuate",
                        "/__probe/capture", "/__probe/profiler"),
                new ProbeRequestPolicy(
                        Duration.ofSeconds(15), Duration.ofMillis(500), 1, false, 3, bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576));
    }

    private static ProbeEndpointResponse substantiveResponse(ProbeEndpointRequest request) {
        String path = request.endpoint().getPath();
        String payload;
        if (path.endsWith("reset")) {
            String key = read(request.payload()).path("key").asText();
            payload = "{\"key\":\"" + key
                    + "\",\"ok\":true,\"lineResolvable\":true,\"lineValidation\":\"resolvable\"}";
        } else if (path.endsWith("status")) {
            String key = queryValue(request, "key");
            payload = "{\"probe\":{\"key\":\"" + key
                    + "\",\"hitCount\":1,\"lastHitEpoch\":9223372036854775807,"
                    + "\"lineResolvable\":true,\"lineValidation\":\"resolvable\"}}";
        } else if (path.endsWith("capture")) {
            payload = "{\"capture\":{\"captureId\":\"capture-1\","
                    + "\"methodKey\":\"com.example.Sample#run\",\"capturedAtEpoch\":1}}";
        } else if (path.endsWith("actuate")) {
            payload = "{\"ok\":true,\"action\":\"disarm\",\"sessionId\":\"session-1\","
                    + "\"scopeState\":\"disarmed\",\"mode\":\"actuate\"}";
        } else if (path.endsWith("profiler")) {
            payload = "{\"ok\":true,\"profiler\":{\"status\":\"idle\",\"supported\":true}}";
        } else {
            throw new AssertionError("unexpected substantive Probe endpoint: " + path);
        }
        return new ProbeEndpointResponse(200, Map.of(), payload, request.configuration());
    }

    private static String queryValue(ProbeEndpointRequest request, String name) {
        String query = request.endpoint().getRawQuery();
        if (query == null) {
            return "mcp.jvm.diagnose#key";
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "mcp.jvm.diagnose#key";
    }

    private SubstantiveCancellationFixture substantiveCancellationFixture(
            String operationId, long timeoutMillis) throws IOException {
        CancellationDelegateFixture delegate = operationId.startsWith("probe.")
                ? productionHttpFixture() : productionJvmHelperFixture();
        List<OperationRegistration<?, ?>> values = operationId.startsWith("probe.")
                ? ProbeOperationRegistrations.create(new ProbeOperationCatalog(
                        substantiveProbeHandlers(delegate.probeClient(), delegate.endpoint())), JSON)
                : JvmLifecycleOperationRegistrations.create(new JvmLifecycleOperationCatalog(
                        substantiveJvmHandlers(delegate.jvmHelper())), JSON);
        OperationRegistration<?, ?> selected = values.stream()
                .filter(value -> value.descriptor().operationId().value().equals(operationId))
                .findFirst().orElseThrow();
        OperationDirectory bounded = new OperationDirectory(
                List.of(withTimeout(selected, timeoutMillis)), documentFor(operationId), JSON);
        return new SubstantiveCancellationFixture(bounded, delegate.state(), delegate.cleanup());
    }

    private static CancellationDelegateFixture productionHttpFixture() throws IOException {
        MutationState state = new MutationState();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> serveControlledMutation(exchange, state));
        server.start();
        ProbeEndpointConfiguration endpoint = endpointConfiguration(
                "http://127.0.0.1:" + server.getAddress().getPort());
        ProbeEndpointClient production = new HttpProbeEndpointClient();
        ProbeEndpointClient observed = request -> {
            try {
                return production.exchange(request);
            } finally {
                state.delegateCompleted.countDown();
            }
        };
        return new CancellationDelegateFixture(observed, null, endpoint, state, () -> server.stop(0));
    }

    private static void serveControlledMutation(HttpExchange exchange, MutationState state) {
        state.beginMutation();
        try {
            state.release.await();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Cancellation can close the client connection before the fixture is released.
        } finally {
            exchange.close();
            state.fixtureCompleted.countDown();
        }
    }

    private static CancellationDelegateFixture productionJvmHelperFixture() throws IOException {
        MutationState state = new MutationState();
        Path mutationLog = Files.createTempFile("mcpjvm-620-helper-mutations-", ".log");
        state.mutationLog.set(mutationLog);
        JvmLifecycleArtifactResolver artifacts =
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("fixture-helper.jar"));
        JvmLifecycleExecutionPolicy policy = new JvmLifecycleExecutionPolicy(
                "java", Duration.ofSeconds(60), Duration.ofSeconds(60), 65536,
                new ProbeHostPolicy(Set.of()));
        JvmLifecycleHelper production = new DefaultJvmLifecycleHelper(
                artifacts, policy, (javaBinary, helperJar, arguments) -> startControlledChild(state), JSON);
        JvmLifecycleHelper observed = request -> {
            try {
                return production.execute(request);
            } finally {
                state.delegateCompleted.countDown();
            }
        };
        return new CancellationDelegateFixture(
                null, observed, endpointConfiguration(), state, () -> cleanupControlledChild(state));
    }

    private static Process startControlledChild(MutationState state) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classPath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                java, "-cp", classPath, ControlledMutationChild.class.getName(),
                state.mutationLog.get().toString()).redirectErrorStream(true).start();
        state.process.set(process);
        state.attempts.incrementAndGet();
        process.onExit().thenRun(() -> state.fixtureCompleted.countDown());
        awaitInitialMutation(process, state.mutationLog.get());
        state.started.countDown();
        return process;
    }

    private static void awaitInitialMutation(Process process, Path mutationLog) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (Files.size(mutationLog) == 0 && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("controlled helper launch was interrupted", exception);
        }
        if (Files.size(mutationLog) == 0) {
            process.destroyForcibly();
            throw new IOException("controlled helper did not record its initial mutation");
        }
    }

    private static void cleanupControlledChild(MutationState state) throws Exception {
        Process process = state.process.get();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        if (process != null) {
            process.waitFor(2, TimeUnit.SECONDS);
        }
        Files.deleteIfExists(state.mutationLog.get());
    }

    private static void assertContainedMutation(SubstantiveCancellationFixture fixture)
            throws Exception {
        MutationState state = fixture.state();
        assertThat(state.delegateCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(state.attempts).hasValue(1);
        assertThat(state.observedMutations()).isEqualTo(1);
        Thread.sleep(200);
        assertThat(state.attempts).hasValue(1);
        assertThat(state.observedMutations()).isEqualTo(1);
        state.release.countDown();
        assertThat(state.fixtureCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(state.fixtureTerminated()).isTrue();
        assertThat(state.attempts).hasValue(1);
        assertThat(state.observedMutations()).isEqualTo(1);
    }

    private OperationDirectory blockingDirectory(
            String operationId,
            CountDownLatch started,
            CountDownLatch interrupted,
            long timeoutMillis) {
        List<ProbeActionHandler> probeHandlers = Arrays.stream(ProbeAction.values())
                .map(action -> new BlockingProbeHandler(action, started, interrupted))
                .map(ProbeActionHandler.class::cast).toList();
        List<JvmLifecycleActionHandler> jvmHandlers = Arrays.stream(JvmLifecycleAction.values())
                .map(action -> new BlockingJvmHandler(action, started, interrupted))
                .map(JvmLifecycleActionHandler.class::cast).toList();
        Stream<OperationRegistration<?, ?>> values = Stream.concat(
                ProbeOperationRegistrations.create(new ProbeOperationCatalog(probeHandlers), JSON).stream(),
                JvmLifecycleOperationRegistrations.create(
                        new JvmLifecycleOperationCatalog(jvmHandlers), JSON).stream());
        OperationRegistration<?, ?> selected = values
                .filter(value -> value.descriptor().operationId().value().equals(operationId))
                .findFirst().orElseThrow();
        OperationRegistration<?, ?> bounded = withTimeout(selected, timeoutMillis);
        return new OperationDirectory(List.of(bounded), documentFor(operationId), JSON);
    }

    private static OperationManifestDocument documentFor(String operationId) {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        OperationId id = OperationId.of(operationId);
        return new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id)));
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

    private static ObjectNode input(String operationId) {
        ObjectNode input = JSON.createObjectNode();
        return switch (operationId) {
            case "probe.actuate" -> input.put("action", "disarm").put("sessionId", "session-1");
            case "probe.capture" -> input.put("captureId", "capture-1");
            case "probe.check", "jvm_lifecycle.list_jvms" -> input;
            case "probe.status" -> input.put("key", "com.example.Sample#run:42");
            case "probe.profiler" -> input.put("action", "start").put("sessionId", "session-1");
            case "probe.reset" -> input.put("key", "com.example.Sample#run:42");
            case "probe.wait_for_hit" -> input.put("key", "com.example.Sample#run:42");
            case "jvm_lifecycle.attach" -> mutationInput(input, true);
            case "jvm_lifecycle.deactivate" -> mutationInput(input, false);
            default -> throw new IllegalArgumentException("unexpected operation: " + operationId);
        };
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static ObjectNode mutationInput(ObjectNode input, boolean attach) {
        input.put("pid", "1234").put("expectedProcessStartEpochMs", 1).put("confirm", true);
        if (attach) {
            input.put("probeHost", "127.0.0.1").put("probePort", 9191);
        }
        return input;
    }

    private static boolean mutating(String operationId) {
        return Set.of("probe.actuate", "probe.profiler", "probe.reset",
                "jvm_lifecycle.attach", "jvm_lifecycle.deactivate").contains(operationId);
    }

    private record CapturingProbeHandler(ProbeAction action) implements ProbeActionHandler {
        @Override
        public ProbeResult execute(ProbeRequest request) {
            return ProbeResult.success();
        }
    }

    private record CapturingJvmHandler(JvmLifecycleAction action) implements JvmLifecycleActionHandler {
        @Override
        public JvmLifecycleResult execute(JvmLifecycleRequest request) {
            return JvmLifecycleResult.blocked("fixture_success");
        }
    }

    private record BlockingProbeHandler(
            ProbeAction action,
            CountDownLatch started,
            CountDownLatch interrupted) implements ProbeActionHandler {
        @Override
        public ProbeResult execute(ProbeRequest request) {
            awaitInterruption(started, interrupted);
            return ProbeResult.success();
        }
    }

    private record BlockingJvmHandler(
            JvmLifecycleAction action,
            CountDownLatch started,
            CountDownLatch interrupted) implements JvmLifecycleActionHandler {
        @Override
        public JvmLifecycleResult execute(JvmLifecycleRequest request) {
            awaitInterruption(started, interrupted);
            return JvmLifecycleResult.blocked("fixture_success");
        }
    }

    private static void awaitInterruption(CountDownLatch started, CountDownLatch interrupted) {
        started.countDown();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
    }

    private record CancellationDelegateFixture(
            ProbeEndpointClient probeClient,
            JvmLifecycleHelper jvmHelper,
            ProbeEndpointConfiguration endpoint,
            MutationState state,
            AutoCloseable cleanup) {
    }

    private record SubstantiveCancellationFixture(
            OperationDirectory directory,
            MutationState state,
            AutoCloseable cleanup) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            state.release.countDown();
            cleanup.close();
        }
    }

    private static final class MutationState {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch delegateCompleted = new CountDownLatch(1);
        private final CountDownLatch fixtureCompleted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicReference<Path> mutationLog = new AtomicReference<>();
        private final AtomicReference<Process> process = new AtomicReference<>();

        private void beginMutation() {
            attempts.incrementAndGet();
            mutations.incrementAndGet();
            started.countDown();
        }

        private int observedMutations() {
            Path log = mutationLog.get();
            if (log == null) {
                return mutations.get();
            }
            try {
                return Files.readAllLines(log).size();
            } catch (IOException exception) {
                throw new IllegalStateException("controlled helper mutation log is unreadable", exception);
            }
        }

        private boolean fixtureTerminated() {
            Process child = process.get();
            return child == null || !child.isAlive();
        }
    }

    /** Real child process used to expose mutation-before-cancellation and possible late mutation. */
    public static final class ControlledMutationChild {

        private ControlledMutationChild() {
        }

        public static void main(String[] arguments) throws Exception {
            Path mutationLog = Path.of(arguments[0]);
            Files.writeString(mutationLog, "initial-mutation\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            Thread.sleep(Duration.ofSeconds(30));
            Files.writeString(mutationLog, "late-mutation\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
    }
}
