package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.DefaultFailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.AnalyzeTraceAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.VerifyReproductionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.HttpFailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerifyEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation.FailureAnalysisOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.DefaultRouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.ClassMethodsAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.CreateRecipeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.DiscoverHandlersAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.InferTargetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.DefaultRouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.SpringHttpHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation.RouteSynthesisOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.DeterministicRouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.DefaultSynthesizerRegistry;
import com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp.SpringHttpSynthesizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Executable Core-directory contract evidence for MCPJVM-621's six rows. */
class RouteFailureOperationRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Set<String> OWNED_IDS = Set.of(
            "route_synthesis.class_methods", "route_synthesis.create_recipe",
            "route_synthesis.discover_handlers", "route_synthesis.infer_target",
            "failure_analysis.analyze_trace", "failure_analysis.verify_reproduction");
    private static final FailureAnalysisPolicy FAILURE_POLICY =
            new FailureAnalysisPolicy(Duration.ofSeconds(15), 200_000, 65_536, 256, 8, 8);

    @TempDir
    Path workspace;

    private Map<String, OperationRegistration<?, ?>> registrations;
    private OperationDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        writeSpringFixture();
        List<OperationRegistration<?, ?>> owned = substantiveRegistrations();
        registrations = owned.stream().collect(java.util.stream.Collectors.toMap(
                value -> value.descriptor().operationId().value(), value -> value,
                (left, right) -> left, LinkedHashMap::new));
        directory = new OperationDirectory(owned, ownedDocument(), JSON);
    }

    @ParameterizedTest(name = "registration {0}")
    @MethodSource("ownedIds")
    void registersExactSubstantiveOwnerSchemaIdentityAndCancellation(String operationId) {
        OperationRegistration<?, ?> registration = registrations.get(operationId);

        assertThat(registration).isNotNull();
        assertThat(registration.descriptor().executableOwner())
                .doesNotContain("DefaultRouteSynthesisFeature", "DefaultFailureAnalysisFeature");
        assertThat(registration.decoder()).isInstanceOf(BoundedOperationRequestDecoder.class);
        assertThat(registration.encoder()).isInstanceOf(BoundedOperationResultEncoder.class);
        assertThat(registration.inputSchema().definition().path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(registration.inputSchema().definition().path("additionalProperties").asBoolean()).isFalse();
        assertThat(registration.provenance().invocationTool())
                .isEqualTo(operationId.substring(0, operationId.indexOf('.')));
        assertThat(registration.provenance().invocationAction())
                .isEqualTo(operationId.substring(operationId.indexOf('.') + 1));
        assertThat(registration.provenance().parityScenario())
                .isEqualTo(operationId.replace('.', '_'));
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.CONTEXT_AWARE_CANCELLATION);
    }

    @ParameterizedTest(name = "omitted versus empty roots {0}")
    @MethodSource("routeIds")
    void preservesOmittedAndExplicitlyEmptyAdditionalSourceRoots(String operationId) {
        ObjectNode omitted = input(operationId).deepCopy();
        ObjectNode explicitEmpty = omitted.deepCopy();
        explicitEmpty.putArray("additionalSourceRoots");

        OperationExecutionResult omittedResult = directory.execute(new OperationInvocation(
                OperationId.of(operationId), omitted, false));
        OperationExecutionResult emptyResult = directory.execute(new OperationInvocation(
                OperationId.of(operationId), explicitEmpty, false));

        assertThat(omittedResult.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(emptyResult.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(emptyResult.result()).isEqualTo(omittedResult.result());
    }

    @ParameterizedTest(name = "execute {0}")
    @MethodSource("ownedIds")
    void executesEveryCanonicalInputThroughTheRealOwner(String operationId) {
        OperationExecutionResult result = directory.execute(new OperationInvocation(
                OperationId.of(operationId), input(operationId), false));

        assertThat(result.status()).as("%s: %s", result.reasonCode(), result.reason())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
        if (operationId.startsWith("route_synthesis.")) {
            assertThat(result.result().path("resultType").asText()).isNotBlank();
        } else {
            assertThat(result.result().path("outcome").asText()).isIn("ANALYZED", "REPRODUCED");
        }
    }

    @ParameterizedTest(name = "unknown field {0}")
    @MethodSource("ownedIds")
    void rejectsUnknownCanonicalFieldsForEveryOwnedRow(String operationId) {
        ObjectNode invalid = input(operationId).deepCopy();
        invalid.put("unknown", true);

        OperationExecutionResult result = directory.execute(new OperationInvocation(
                OperationId.of(operationId), invalid, false));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("operation_input_schema_invalid");
    }

    @Test
    void preservesCandidateOrderingAndDeterministicFailureOutcomes() throws Exception {
        writeController("alpha", "AmbiguousController", "/alpha");
        writeController("beta", "AmbiguousController", "/beta");
        OperationExecutionResult ambiguous = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.infer_target"),
                routeInput("AmbiguousController", "run"), false));
        ObjectNode outside = routeInput("example.StdioController", null);
        outside.put("projectRootAbs", workspace.resolve("missing").toString());
        OperationExecutionResult invalidRoot = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.class_methods"), outside, false));
        OperationExecutionResult unsupported = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.discover_handlers"),
                routeInput("example.PlainController", null), false));

        JsonNode candidates = ambiguous.result().path("actionResult").path("candidates");
        assertThat(ambiguous.result().path("resultType").asText()).isEqualTo("disambiguation");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).path("fqcn").asText()).isEqualTo("alpha.AmbiguousController");
        assertThat(candidates.get(1).path("fqcn").asText()).isEqualTo("beta.AmbiguousController");
        assertThat(invalidRoot.result().path("reasonCode").asText())
                .as(invalidRoot.toString())
                .isEqualTo("project_selector_invalid");
        assertThat(unsupported.result().path("reasonCode").asText()).isEqualTo("mapper_plugin_unavailable");
    }

    @Test
    void comparesReleasedTypeScriptMapperAndSynthesizerSemanticsThroughCdeBindings() throws Exception {
        JsonNode released = releasedTypeScriptRouteResult();
        JsonNode javaOwner = JSON.valueToTree(new DefaultRouteSynthesisFeature(routeHandlers())
                .execute(JSON.convertValue(recipeInput(), CreateRecipeRequest.class)));
        OperationExecutionResult handlers = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.discover_handlers"),
                routeInput("example.StdioController", null), false));
        OperationExecutionResult recipe = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.create_recipe"), recipeInput(), false));

        JsonNode mapperCandidate = released.path("mapping").path("requestCandidate");
        JsonNode synthesizerCandidate = released.path("synthesis").path("requestCandidate");
        JsonNode cdeHandler = handlers.result().path("actionResult").path("handlers").get(0);
        JsonNode cdeCandidate = recipe.result().path("actionResult").path("requestCandidates").get(0);
        assertThat(released.path("mapping").path("status").asText()).isEqualTo("ok");
        assertThat(released.path("mapping").path("framework").asText()).isEqualTo("spring_mvc");
        assertThat(cdeHandler.path("httpMethod")).isEqualTo(mapperCandidate.path("method"));
        assertThat(cdeHandler.path("path")).isEqualTo(mapperCandidate.path("path"));
        assertThat(released.path("synthesis").path("status").asText()).isEqualTo("recipe");
        assertThat(recipe.result().path("resultType").asText()).isEqualTo("recipe");
        assertThat(javaOwner.path("actionResult").path("synthesizerUsed").asText()).isEqualTo("spring_http");
        assertThat(released.path("synthesis").path("synthesizerUsed").asText()).isEqualTo("spring");
        assertThat(recipe.result().path("actionResult").path("synthesizerUsed"))
                .isEqualTo(released.path("synthesis").path("synthesizerUsed"));
        var identity = registrations.get("route_synthesis.create_recipe").provenance();
        assertThat(identity.normalization()).isEqualTo(
                "create_recipe.synthesizerUsed:java_owner=spring_http->cde=spring=typescript_released_spring");
        assertThat(identity.resultComparison()).isEqualTo(
                "route_result_envelope_and_MCPJVM-621_approved_cde_spring_mapping_and_recipe_semantics");
        assertThat(cdeCandidate.path("method")).isEqualTo(synthesizerCandidate.path("method"));
        assertThat(cdeCandidate.path("path")).isEqualTo(synthesizerCandidate.path("path"));
    }

    @Test
    void redactsCredentialsTraceSecretsAndLocalPaths() {
        ObjectNode recipe = input("route_synthesis.create_recipe");
        recipe.put("authToken", "Bearer route-secret");
        OperationExecutionResult route = directory.execute(new OperationInvocation(
                OperationId.of("route_synthesis.create_recipe"), recipe, false));
        OperationExecutionResult failure = directory.execute(new OperationInvocation(
                OperationId.of("failure_analysis.analyze_trace"),
                input("failure_analysis.analyze_trace"), false));

        assertThat(route.result().toString()).doesNotContain("route-secret", workspace.toString());
        assertThat(failure.result().toString()).doesNotContain("trace-secret", "sidecar-secret");
        assertThat(failure.result().path("fingerprint").path("normalizedMessage").asText())
                .isEqualTo("<redacted>");
    }

    @ParameterizedTest(name = "timeout {0}")
    @MethodSource("ownedIds")
    void deadlineStopsEveryRealOwnerAndContainsPostTimeoutWork(String operationId) throws Exception {
        HttpServer sidecar = null;
        CountDownLatch sidecarStarted = new CountDownLatch(1);
        ObjectNode request = input(operationId).deepCopy();
        List<FailureAnalysisActionHandler> handlers = failureHandlers();
        if (operationId.startsWith("route_synthesis.")) {
            writeLargeSource();
        } else {
            sidecar = delayedSidecar(sidecarStarted);
            request.put("sidecarBaseUrl", "http://127.0.0.1:" + sidecar.getAddress().getPort());
            handlers = httpFailureHandlers();
        }
        try {
            OperationDirectory bounded = boundedDirectory(operationId, handlers);
            OperationExecutionResult result = bounded.execute(new OperationInvocation(
                    OperationId.of(operationId), request, false));

            assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
            assertThat(result.reasonCode()).isEqualTo("operation_timeout");
            if (!operationId.startsWith("route_synthesis.")) {
                assertThat(sidecarStarted.await(1, TimeUnit.SECONDS)).isTrue();
            }
            assertNoActiveOperationFrame(operationId.startsWith("route_synthesis.")
                    ? "FileSystemJavaSourceDiscovery" : "HttpFailureEvidenceClient");
            Files.deleteIfExists(largeSource());
            OperationExecutionResult contained = directory.execute(new OperationInvocation(
                    OperationId.of("route_synthesis.class_methods"),
                    input("route_synthesis.class_methods"), false));
            assertThat(contained.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        } finally {
            if (sidecar != null) {
                sidecar.stop(0);
            }
        }
    }

    @Test
    void realSourceDiscoveryStopsForDeadlineCallerInterruptionAndContainsCancellation() throws Exception {
        FileSystemJavaSourceDiscovery source =
                new FileSystemJavaSourceDiscovery(new RouteSynthesisWorkspaceSnapshot(workspace));
        writeLargeSource();
        assertInProgressStop(source, OperationExecutionContext.forTimeout(100), false);
        OperationExecutionContext cancelled = OperationExecutionContext.unbounded();
        assertInProgressStop(source, cancelled, false, cancelled::requestCancellation);
        assertInProgressStop(source, OperationExecutionContext.unbounded(), true);

        Files.deleteIfExists(largeSource());
        try (var ignored = OperationExecutionContext.unbounded().install()) {
            assertThat(source.discover(workspace, List.of(), null).scannedJavaFiles()).isPositive();
        }
    }

    @Test
    void writesSixRowManifestTraceAndRoutingEvidence() throws Exception {
        assertThat(directory.manifest().descriptors()).hasSize(6);
        assertThat(directory.traceInventory()).hasSize(6).allSatisfy(entry ->
                assertThat(entry.compatibility()).containsKeys(
                        "normalization", "resultComparison", "parityScenario", "cancellationState"));
        Path evidence = Path.of("target", "mcpjvm-621-evidence");
        Files.createDirectories(evidence);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("manifest.json").toFile(), directory.manifest().descriptors());
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-inventory.json").toFile(), directory.traceInventory());
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("routing-inventory.json").toFile(), routingInventory());
    }

    static Stream<String> ownedIds() {
        return OWNED_IDS.stream().sorted();
    }

    static Stream<String> routeIds() {
        return OWNED_IDS.stream().filter(id -> id.startsWith("route_synthesis.")).sorted();
    }

    private JsonNode releasedTypeScriptRouteResult() throws Exception {
        String script = """
                (async () => {
                  const { resolveRequestMappingAst } =
                    require('./tools/transport/tools-mcp-server/src/lib/request_mapping_ast_resolver');
                  const { synthesizeSpringRecipe } =
                    require('./tools/synthesizers/tools-spring-http/src/synthesis.util');
                  const root = process.env.MCPJVM_PARITY_ROOT;
                  const mapping = await resolveRequestMappingAst({
                    projectRootAbs: root,
                    searchRootsAbs: [root],
                    classHint: 'example.StdioController',
                    methodHint: 'run'
                  });
                  const synthesis = await synthesizeSpringRecipe({
                    rootAbs: root,
                    workspaceRootAbs: root,
                    searchRootsAbs: [root],
                    classHint: 'example.StdioController',
                    methodHint: 'run',
                    intentMode: 'regression'
                  }, { resolveRequestMappingFn: async () => mapping });
                  console.log('MCPJVM_CDE_PARITY=' + JSON.stringify({ mapping, synthesis }));
                })().catch(error => { console.error(error); process.exit(1); });
                """;
        Path root = repositoryRoot();
        ProcessBuilder builder = new ProcessBuilder(
                "node", "-r", "ts-node/register", "-r", "tsconfig-paths/register", "-e", script)
                .directory(root.toFile())
                .redirectErrorStream(true);
        builder.environment().put("MCPJVM_PARITY_ROOT", workspace.toString());
        builder.environment().put("MCP_JAVA_BIN", Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString());
        Process process = builder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(completed).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        String marker = "MCPJVM_CDE_PARITY=";
        String payload = output.lines().filter(line -> line.startsWith(marker))
                .map(line -> line.substring(marker.length())).findFirst().orElseThrow();
        return JSON.readTree(payload);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("tools/contracts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current;
    }

    private List<OperationRegistration<?, ?>> substantiveRegistrations() {
        return Stream.concat(
                RouteSynthesisOperationRegistrations.create(
                        new DefaultRouteSynthesisFeature(routeHandlers()), JSON).stream(),
                FailureAnalysisOperationRegistrations.create(
                        new DefaultFailureAnalysisFeature(failureHandlers()), JSON).stream()).toList();
    }

    private List<RouteSynthesisActionHandler> routeHandlers() {
        RouteSynthesisWorkspaceSnapshot snapshot = new RouteSynthesisWorkspaceSnapshot(workspace);
        FileSystemJavaSourceDiscovery source = new FileSystemJavaSourceDiscovery(snapshot);
        SpringHttpHandlerDiscovery handlers = new SpringHttpHandlerDiscovery(source);
        var workspaceProvider = (com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace
                .RouteSynthesisWorkspaceProvider) () -> java.util.Optional.of(snapshot);
        var route = (com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing
                .RouteSynthesisProbeRouteResolver) (probeId, baseUrl) ->
                RouteSynthesisProbeRouteResolution.resolved("http://127.0.0.1:9191");
        var runtime = (com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime
                .RouteSynthesisRuntimeEvidenceProvider) (key, start, end, resolved) ->
                RouteSynthesisRuntimeLineResolution.resolved(start);
        return List.of(
                new InferTargetAction(workspaceProvider, source, route, runtime,
                        new DeterministicRouteTargetRanker()),
                new ClassMethodsAction(workspaceProvider, source, route, runtime),
                new DiscoverHandlersAction(workspaceProvider, handlers, route, runtime),
                new CreateRecipeAction(workspaceProvider, handlers,
                        new DefaultRouteSynthesisAuthenticationResolver(),
                        new DefaultSynthesizerRegistry(new SpringHttpSynthesizer(), 0)));
    }

    private List<FailureAnalysisActionHandler> failureHandlers() {
        FailureEvidenceResponseMapper mapper = new FailureEvidenceResponseMapper(FAILURE_POLICY);
        FailureEvidenceClient client = new EvidenceClient();
        return List.of(
                new AnalyzeTraceAction(client, mapper, FAILURE_POLICY),
                new VerifyReproductionAction(client, mapper, FAILURE_POLICY));
    }

    private OperationDirectory boundedDirectory(
            String operationId, List<FailureAnalysisActionHandler> selectedFailureHandlers) {
        Stream<OperationRegistration<?, ?>> values = Stream.concat(
                RouteSynthesisOperationRegistrations.create(
                        new DefaultRouteSynthesisFeature(routeHandlers()), JSON).stream(),
                FailureAnalysisOperationRegistrations.create(
                        new DefaultFailureAnalysisFeature(selectedFailureHandlers), JSON).stream());
        OperationRegistration<?, ?> selected = values
                .filter(value -> value.descriptor().operationId().value().equals(operationId))
                .findFirst().orElseThrow();
        return new OperationDirectory(List.of(withTimeout(selected, 100)), documentFor(operationId), JSON);
    }

    private List<FailureAnalysisActionHandler> httpFailureHandlers() {
        FailureEvidenceResponseMapper mapper = new FailureEvidenceResponseMapper(FAILURE_POLICY);
        HttpFailureEvidenceClient client = new HttpFailureEvidenceClient(JSON, FAILURE_POLICY);
        return List.of(
                new AnalyzeTraceAction(client, mapper, FAILURE_POLICY),
                new VerifyReproductionAction(client, mapper, FAILURE_POLICY));
    }

    private HttpServer delayedSidecar(CountDownLatch started) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__probe/failure/analyze", exchange -> delayedResponse(exchange, started));
        server.createContext("/__probe/failure/verify", exchange -> delayedResponse(exchange, started));
        server.start();
        return server;
    }

    private static void delayedResponse(HttpExchange exchange, CountDownLatch started) throws IOException {
        started.countDown();
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void assertInProgressStop(
            FileSystemJavaSourceDiscovery source,
            OperationExecutionContext context,
            boolean interrupt) throws Exception {
        assertInProgressStop(source, context, interrupt, () -> {
        });
    }

    private void assertInProgressStop(
            FileSystemJavaSourceDiscovery source,
            OperationExecutionContext context,
            boolean interrupt,
            Runnable cancellation) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            try (var ignored = context.install()) {
                source.discover(workspace, List.of(), null);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "mcpjvm-621-real-source");
        worker.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitFrame(worker, "FileSystemJavaSourceDiscovery")).isTrue();
        cancellation.run();
        if (interrupt) {
            worker.interrupt();
        }
        worker.join(2_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(CancellationException.class);
    }

    private static boolean awaitFrame(Thread worker, String className) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (worker.isAlive() && System.nanoTime() < deadline) {
            for (StackTraceElement frame : worker.getStackTrace()) {
                if (frame.getClassName().contains(className)) {
                    return true;
                }
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static void assertNoActiveOperationFrame(String className) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            boolean active = Thread.getAllStackTraces().entrySet().stream()
                    .filter(entry -> entry.getKey().getName().equals("mcpjvm-operation"))
                    .flatMap(entry -> Arrays.stream(entry.getValue()))
                    .anyMatch(frame -> frame.getClassName().contains(className));
            if (!active) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("operation worker retained an active " + className + " frame");
    }

    private void writeLargeSource() throws IOException {
        Path source = largeSource();
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; public class Slow { /*"
                + "x".repeat(32_000_000) + "*/ public void slow() { int value = 1; } }");
    }

    private Path largeSource() {
        return workspace.resolve("src/main/java/example/Slow.java");
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

    private ObjectNode input(String operationId) {
        return switch (operationId) {
            case "route_synthesis.infer_target" -> routeInput("example.StdioController", "run");
            case "route_synthesis.class_methods", "route_synthesis.discover_handlers" ->
                    routeInput("example.StdioController", null);
            case "route_synthesis.create_recipe" -> recipeInput();
            case "failure_analysis.analyze_trace" -> analyzeInput();
            case "failure_analysis.verify_reproduction" -> verifyInput();
            default -> throw new IllegalArgumentException("unexpected operation: " + operationId);
        };
    }

    private ObjectNode routeInput(String classHint, String methodHint) {
        ObjectNode input = JSON.createObjectNode()
                .put("projectRootAbs", workspace.toString())
                .put("classHint", classHint);
        if (methodHint != null) {
            input.put("methodHint", methodHint);
        }
        input.put("probeBaseUrl", "http://127.0.0.1:9191");
        return input;
    }

    private ObjectNode recipeInput() {
        return routeInput("example.StdioController", "run")
                .put("intentMode", "regression")
                .put("discoveryPreference", "static_only");
    }

    private static ObjectNode analyzeInput() {
        return JSON.createObjectNode()
                .put("trace", "java.lang.IllegalStateException: trace-secret")
                .put("sidecarBaseUrl", "http://127.0.0.1:9191")
                .put("sidecarAuthorization", "Bearer sidecar-secret")
                .put("timeoutMs", 2_000);
    }

    private static ObjectNode verifyInput() {
        ObjectNode input = JSON.createObjectNode()
                .put("captureId", "capture-621")
                .put("sidecarBaseUrl", "http://127.0.0.1:9191")
                .put("timeoutMs", 2_000);
        input.putObject("expectedFingerprint")
                .put("exceptionType", "java.lang.IllegalStateException")
                .put("rootCauseType", "java.lang.IllegalArgumentException")
                .put("nearestApplicationMethodKey", "example.StdioController#run:7");
        input.putObject("lineHit")
                .put("strictLineKey", "example.StdioController#run:7")
                .put("hitCount", 1);
        return input;
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

    private static OperationManifestDocument documentFor(String operationId) {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        OperationId id = OperationId.of(operationId);
        return new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id)));
    }

    private Map<String, Object> routingInventory() throws Exception {
        Path root = Path.of("src", "main", "java", "com", "nimbly", "mcpjavadevtools", "server", "core", "feature");
        List<Map<String, String>> compatibility = List.of(
                retained(root.resolve("routesynthesis/DefaultRouteSynthesisFeature.java"),
                        "RouteSynthesisMcpTool"),
                retained(root.resolve("failureanalysis/DefaultFailureAnalysisFeature.java"),
                        "FailureAnalysisMcpTool"));
        for (Map<String, String> entry : compatibility) {
            assertThat(Files.readString(Path.of(entry.get("path"))))
                    .contains("COMPATIBILITY_RETAINED_UNTIL_611");
        }
        List<Map<String, Object>> measured = List.of(
                measuredRoutingFile(root.resolve("routesynthesis/DefaultRouteSynthesisFeature.java"), 40),
                measuredRoutingFile(root.resolve("failureanalysis/DefaultFailureAnalysisFeature.java"), 26),
                measuredRoutingFile(root.resolve(
                        "routesynthesis/operation/RouteSynthesisOperationRegistrations.java"), 138),
                measuredRoutingFile(root.resolve(
                        "failureanalysis/operation/FailureAnalysisOperationRegistrations.java"), 145),
                measuredRoutingFile(root.resolve(
                        "routesynthesis/operation/RouteSynthesisOperationSchemas.java"), 0),
                measuredRoutingFile(root.resolve(
                        "routesynthesis/operation/RouteSynthesisResultEncoder.java"), 0),
                measuredRoutingFile(root.resolve(
                        "failureanalysis/operation/FailureAnalysisResultSchema.java"), 0));
        int grossDelta = measured.stream().mapToInt(entry -> (Integer) entry.get("delta")).sum();
        List<Map<String, Object>> exclusions = List.of(
                excludedDelta("required schema/model", 44, 103),
                excludedDelta("lossless bounded binding", 4, 15),
                excludedDelta("context-aware cancellation", 0, 23),
                excludedDelta("released TypeScript result normalization and compatibility metadata", 0, 49));
        int excludedDelta = exclusions.stream().mapToInt(entry -> (Integer) entry.get("delta")).sum();
        int assessedDelta = grossDelta - excludedDelta;
        assertThat(assessedDelta).isLessThanOrEqualTo(0);
        return Map.ofEntries(
                Map.entry("baselineRevision", "2f73cef80a65dcc15f6e83ac429f1bb26d6fed4c"),
                Map.entry("operationIds", OWNED_IDS.stream().sorted().toList()),
                Map.entry("measure", "nonblank source lines"),
                Map.entry("files", measured),
                Map.entry("grossNonblankDelta", grossDelta),
                Map.entry("excludedRequiredSchemaAndSubstantiveBehavior", exclusions),
                Map.entry("routingPlumbingNonblankDelta", assessedDelta),
                Map.entry("added", List.of()),
                Map.entry("substantiveAdded", List.of("RouteSynthesisResultEncoder")),
                Map.entry("removed", List.of()),
                Map.entry("retained", List.of(
                        "RouteSynthesisActionHandler", "FailureAnalysisActionHandler",
                        "RouteSynthesisOperationRegistrations", "FailureAnalysisOperationRegistrations")),
                Map.entry("compatibilityRetained", compatibility));
    }

    private static Map<String, Object> measuredRoutingFile(Path path, int before) throws Exception {
        int after = (int) Files.readAllLines(path).stream().filter(line -> !line.isBlank()).count();
        return Map.of("path", path.toString(), "before", before, "after", after, "delta", after - before);
    }

    private static Map<String, Object> excludedDelta(String reason, int before, int after) {
        return Map.of("reason", reason, "before", before, "after", after, "delta", after - before);
    }

    private static Map<String, String> retained(Path path, String caller) {
        return Map.of(
                "path", path.toString(),
                "caller", caller,
                "deletionCondition", "#611 legacy adapter removal");
    }

    private void writeSpringFixture() throws Exception {
        writeController("example", "StdioController", "/stdio/run");
        Path plain = workspace.resolve("src/main/java/example/PlainController.java");
        Files.createDirectories(plain.getParent());
        Files.writeString(plain, """
                package example;
                public class PlainController {
                    public String run() {
                        return "ok";
                    }
                }
                """);
    }

    private void writeController(String packageName, String className, String path) throws Exception {
        Path source = workspace.resolve("src/main/java")
                .resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package %s;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class %s {
                    @GetMapping("%s")
                    public String run() {
                        return "ok";
                    }
                }
                """.formatted(packageName, className, path));
    }

    private static Map<String, Object> evidencePayload() {
        return Map.of(
                "fingerprint", Map.of(
                        "exceptionType", "java.lang.IllegalStateException",
                        "rootCauseType", "java.lang.IllegalArgumentException",
                        "nearestApplicationMethodKey", "example.StdioController#run:7",
                        "normalizedMessage", "Bearer trace-secret",
                        "complete", true),
                "investigationCandidates", List.of(),
                "exceptionSections", List.of(),
                "reasons", List.of());
    }

    private static class EvidenceClient implements FailureEvidenceClient {

        @Override
        public FailureEvidenceResponse analyze(FailureAnalyzeEvidenceRequest request) {
            return new FailureEvidenceResponse(200, JSON.valueToTree(evidencePayload()));
        }

        @Override
        public FailureEvidenceResponse verify(FailureVerifyEvidenceRequest request) {
            Map<String, Object> observed = Map.of(
                    "exceptionType", "java.lang.IllegalStateException",
                    "rootCauseType", "java.lang.IllegalArgumentException",
                    "nearestApplicationMethodKey", "example.StdioController#run:7",
                    "complete", true);
            return new FailureEvidenceResponse(200, JSON.valueToTree(
                    Map.of("outcome", "matched", "observedFingerprint", observed)));
        }
    }
}
