package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl.ExecuteTransportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.custom.CustomTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.grpc.GrpcTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpSensitiveDataRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.kafka.KafkaTransportProvider;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves the published Java branches against the released TypeScript transport contract. */
class TransportExecuteMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void provesSuccessAndRepresentativeFailureParityMatrix() throws Exception {
        assertReleasedTypeScriptEvidence();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, "{\"safe\":\"parity-ok\"}"));
        server.start();
        try (InputStream stream = getClass().getResourceAsStream(
                "/transport-execute/transport-execute-typescript-java-parity.json")) {
            assertThat(stream).isNotNull();
            for (JsonNode testCase : JSON.readTree(stream).path("cases")) {
                assertFixture(testCase, response(testCase.path("kind").asText(), server));
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertReleasedTypeScriptEvidence() throws IOException {
        Path root = repositoryRoot();
        String action = Files.readString(root.resolve(
                "tools/features/transport-execution/actions/execute_transport.action.ts"));
        String http = Files.readString(root.resolve(
                "tools/features/transport-execution/support/execute_http_request.ts"));
        String input = Files.readString(root.resolve(
                "tools/contracts/tools-contracts/src/inputs/transport_execute.input.model.ts"));
        assertThat(action).contains("failedStep: \"transport_execute_policy\"")
                .contains("failedStep: \"transport_execute_protocol\"");
        assertThat(http).contains("failedStep: \"transport_execute_http_payload\"")
                .contains("failedStep: \"transport_execute_http\"");
        assertThat(input).contains("z.enum([\"http\", \"grpc\", \"kafka\", \"custom\"])");
    }

    private static void assertFixture(JsonNode testCase, McpActionResponse response) {
        JsonNode actual = JSON.valueToTree(response);
        Iterator<Entry<String, JsonNode>> assertions = testCase.path("assertions").fields();
        while (assertions.hasNext()) {
            Entry<String, JsonNode> assertion = assertions.next();
            assertThat(actual.at(assertion.getKey()).toString())
                    .as("%s %s", testCase.path("name").asText(), assertion.getKey())
                    .isEqualTo(assertion.getValue().toString());
        }
    }

    private static McpActionResponse response(String kind, HttpServer server) throws IOException {
        boolean allowNonWrapped = kind.equals("wrapper_blocked");
        TransportExecuteMcpTool tool = new TransportExecuteMcpTool(feature(allowNonWrapped));
        TransportExecuteMcpInput input = switch (kind) {
            case "success" -> input("http", Map.of("method", "GET", "url", url(server)));
            case "wrapper_blocked" -> input("http", Map.of());
            case "unsupported_protocol" -> input("grpc", Map.of("target", "service"));
            case "invalid_http_payload" -> input("http", Map.of("url", url(server)));
            case "runtime_failure" -> input("http", Map.of(
                    "method", "GET", "url", "http://127.0.0.1:" + freePort() + "/"));
            default -> throw new IllegalArgumentException("Unknown transport fixture: " + kind);
        };
        return tool.invokeMcpRequest(input);
    }

    private static TransportExecuteMcpInput input(String protocol, Map<String, Object> request) {
        return new TransportExecuteMcpInput(protocol, request, null);
    }

    private static TransportExecutionFeature feature(boolean allowNonWrapped) {
        HttpTransportSafetyPolicy policy = new HttpTransportSafetyPolicy(Set.of());
        HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        TransportProvider http = new HttpTransportProvider(
                new HttpRequestValidator(policy, redactor, JSON),
                new HttpRedirectResponseExecutor(client, policy, redactor));
        TransportProviderRegistry providers = new TransportProviderRegistry(List.of(
                http, new GrpcTransportProvider(), new KafkaTransportProvider(), new CustomTransportProvider()));
        return new DefaultTransportExecutionFeature(List.of(
                new ExecuteTransportAction(() -> allowNonWrapped, providers)));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
}
