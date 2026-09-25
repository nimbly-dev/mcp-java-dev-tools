package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl.ExecuteTransportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.operation.TransportExecutionOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.TransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpSensitiveDataRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportExecutionFeatureTest {

    @Test
    void dispatchesTheInternalExecuteActionWithoutAddingPublicActionInput() {
        TransportExecutionFeature feature = feature(() -> false);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "http://127.0.0.1"),
                true));

        assertThat(result.status()).isEqualTo("pass");
        assertThat(result.protocol()).isEqualTo("http");
    }

    @Test
    void blocksWrappedOnlyWhenTheActiveProbeRegistryAllowsBypass() {
        TransportExecutionFeature feature = feature(() -> true);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP, Map.of("method", "GET", "url", "http://127.0.0.1"), true));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("wrapper_policy_violation");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "transport_execute_policy")
                .containsEntry("protocol", "http");
    }

    @Test
    void unsupportedProvidersRemainDeterministic() {
        TransportExecutionFeature feature = feature(() -> false);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.GRPC, Map.of("target", "service"), true));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("transport_not_supported");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "transport_execute_protocol")
                .containsEntry("protocol", "grpc");
    }

    @Test
    void registersTheActionlessTransportRowAgainstItsSubstantiveOwner() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var registration = TransportExecutionOperationRegistrations.create(feature(() -> false), mapper).getFirst();

        assertThat(registration.descriptor().operationId().value()).isEqualTo("transport_execute.execute");
        assertThat(registration.descriptor().executableOwner()).contains("ExecuteTransportAction#execute");
        assertThat(registration.provenance().actionless()).isTrue();
        assertThat(registration.provenance().invocationAction()).isEmpty();
        assertThat(OperationCancellationSupport.state(registration.executor(), registration.safety()))
                .isEqualTo(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION);

        var result = registration.execute(mapper.readTree("""
                {"protocol":"http","request":{"method":"GET","url":"http://127.0.0.1"},
                "options":{"wrappedOnly":true}}
                """));
        assertThat(result.path("status").asText()).isEqualTo("pass");
        assertThat(result.path("protocol").asText()).isEqualTo("http");

        writeContractEvidence(registration, mapper);
    }

    @Test
    void concreteLoopbackRegistrationMatchesTypedOwnerAndContainsPostTimeoutWork() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer target = server(exchange -> respond(exchange, 200, "{\"message\":\"core-parity\"}"));
        try {
            AtomicInteger ownerCalls = new AtomicInteger();
            AtomicReference<TransportExecutionRequest> ownerRequest = new AtomicReference<>();
            AtomicReference<ExecuteTransportResult> ownerResult = new AtomicReference<>();
            DefaultTransportExecutionFeature feature = realFeature(ownerCalls, ownerRequest, ownerResult);
            var registration = TransportExecutionOperationRegistrations.create(feature, mapper).getFirst();
            OperationDirectory directory = directory(registration, mapper);
            Map<String, Object> request = Map.of(
                    "method", "GET", "url", url(target),
                    "headers", Map.of("Authorization", "Bearer core-secret"));

            ExecuteTransportResult typed = feature.execute(new ExecuteTransportRequest(
                    TransportProtocol.HTTP, request, true));
            ownerCalls.set(0);
            ownerRequest.set(null);
            ownerResult.set(null);
            ObjectNode input = mapper.createObjectNode().put("protocol", "http");
            input.set("request", mapper.valueToTree(request));
            input.putObject("options").put("wrappedOnly", true);
            OperationExecutionResult cde = directory.execute(new OperationInvocation(
                    OperationId.of("transport_execute.execute"), input, true));

            assertThat(cde.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
            assertThat(ownerCalls).hasValue(1);
            assertThat(ownerRequest).hasValue(new ExecuteTransportRequest(
                    TransportProtocol.HTTP, request, true));
            assertThat(cde.result().path("status").asText()).isEqualTo(typed.status());
            assertThat(cde.result().path("statusCode").asInt()).isEqualTo(typed.statusCode());
            assertThat(cde.result().path("protocol").asText()).isEqualTo(ownerResult.get().protocol());
            assertThat(cde.result().path("headers")).isEqualTo(mapper.valueToTree(ownerResult.get().headers()));
            assertThat(cde.result().path("bodyPreview").asText()).isEqualTo(ownerResult.get().bodyPreview())
                    .contains("core-parity");
            assertThat(cde.result().path("durationMs").asLong()).isEqualTo(ownerResult.get().durationMs());
            assertThat(registration.provenance().resultComparison())
                    .isEqualTo("transport_status_protocol_headers_body_and_duration_preserved");
            assertThat(registration.provenance().parityScenario())
                    .isEqualTo("transport_execute_public_request_contract");
            assertThat(cde.result().toString()).doesNotContain("core-secret");
        } finally {
            target.stop(0);
        }

        CountDownLatch started = new CountDownLatch(1);
        HttpServer delayed = server(exchange -> {
            started.countDown();
            pause(5_000);
            respond(exchange, 200, "{\"message\":\"late\"}");
        });
        try {
            DefaultTransportExecutionFeature feature = realFeature();
            OperationRegistration<?, ?> registration = TransportExecutionOperationRegistrations
                    .create(feature, mapper).getFirst();
            OperationDirectory bounded = directory(withTimeout(registration, 100), mapper);
            ObjectNode input = mapper.createObjectNode().put("protocol", "http");
            input.set("request", mapper.valueToTree(Map.of("method", "GET", "url", url(delayed))));

            OperationExecutionResult timedOut = bounded.execute(new OperationInvocation(
                    OperationId.of("transport_execute.execute"), input, true));

            assertThat(timedOut.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
            assertThat(timedOut.reasonCode()).isEqualTo("operation_timeout");
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertNoActiveTransportFrame();
        } finally {
            delayed.stop(0);
        }

        HttpServer recovery = server(exchange -> respond(exchange, 200, "{\"message\":\"recovered\"}"));
        try {
            var registration = TransportExecutionOperationRegistrations.create(realFeature(), mapper).getFirst();
            ObjectNode input = mapper.createObjectNode().put("protocol", "http");
            input.set("request", mapper.valueToTree(Map.of("method", "GET", "url", url(recovery))));
            assertThat(directory(registration, mapper).execute(new OperationInvocation(
                    OperationId.of("transport_execute.execute"), input, true)).status())
                    .isEqualTo(OperationExecutionStatus.SUCCEEDED);
        } finally {
            recovery.stop(0);
        }
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
                evidence.resolve("manifest-transport-execution.json").toFile(),
                directory.manifest().descriptors());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("trace-transport-execution.json").toFile(), directory.traceInventory());
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

    private DefaultTransportExecutionFeature realFeature() {
        return realFeature(null, null, null);
    }

    private DefaultTransportExecutionFeature realFeature(
            AtomicInteger ownerCalls, AtomicReference<TransportExecutionRequest> ownerRequest,
            AtomicReference<ExecuteTransportResult> ownerResult) {
        HttpTransportSafetyPolicy httpPolicy = new HttpTransportSafetyPolicy(java.util.Set.of());
        HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();
        HttpTransportProvider http = new HttpTransportProvider(
                new HttpRequestValidator(httpPolicy, redactor, new ObjectMapper()),
                new HttpRedirectResponseExecutor(
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                        httpPolicy, redactor));
        ExecuteTransportAction owner = new ExecuteTransportAction(() -> false,
                new TransportProviderRegistry(List.of(http, provider(TransportProtocol.GRPC),
                        provider(TransportProtocol.KAFKA), provider(TransportProtocol.CUSTOM))));
        if (ownerCalls == null) {
            return new DefaultTransportExecutionFeature(List.of(owner));
        }
        TransportExecutionActionHandler observed = new TransportExecutionActionHandler() {
            @Override
            public TransportExecutionAction action() {
                return owner.action();
            }

            @Override
            public ExecuteTransportResult execute(TransportExecutionRequest request) {
                ownerCalls.incrementAndGet();
                ownerRequest.set(request);
                ExecuteTransportResult result = owner.execute(request);
                ownerResult.set(result);
                return result;
            }
        };
        return new DefaultTransportExecutionFeature(List.of(observed));
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertNoActiveTransportFrame() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean active = Thread.getAllStackTraces().values().stream().flatMap(java.util.Arrays::stream)
                    .anyMatch(frame -> frame.getClassName().contains("HttpRedirectResponseExecutor"));
            if (!active) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("transport worker retained an active HTTP execution frame");
    }

    private DefaultTransportExecutionFeature feature(TransportExecutionPolicy policy) {
        TransportProviderRegistry providers = new TransportProviderRegistry(List.of(
                provider(TransportProtocol.HTTP),
                provider(TransportProtocol.GRPC),
                provider(TransportProtocol.KAFKA),
                provider(TransportProtocol.CUSTOM)));
        TransportExecutionActionHandler action = new ExecuteTransportAction(policy, providers);
        return new DefaultTransportExecutionFeature(List.of(action));
    }

    private TransportProvider provider(TransportProtocol protocol) {
        return new TransportProvider() {
            @Override
            public TransportProtocol protocol() {
                return protocol;
            }

            @Override
            public ExecuteTransportResult execute(ExecuteTransportRequest request) {
                return protocol == TransportProtocol.HTTP
                        ? ExecuteTransportResult.httpResponse("pass", protocol.value(), 200, Map.of(), null, 1)
                        : ExecuteTransportResult.unsupported(protocol.value());
            }
        };
    }
}
