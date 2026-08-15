package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RouteSynthesisRuntimeMappingsHttpAdapterTest {

    @Test
    void resolvesLocalMappingWithBearerTokenAndRedactedEvidence() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/mappings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!"Bearer secret-token".equals(authorization.get())) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            respond(exchange, "{\"handler\":\"example.Controller#run()\","
                    + "\"predicate\":\"{GET [/orders]}\"}");
        });
        server.start();
        try {
            RouteSynthesisRuntimeMappingsHttpAdapter adapter = adapter(4096);
            RouteSynthesisRuntimeMappingResolution result = adapter.resolve(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/mappings",
                    "example.Controller", "run", "secret-token");

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.requestCandidate().path()).isEqualTo("/orders");
            assertThat(authorization.get()).isEqualTo("Bearer secret-token");
            assertThat(result.evidence()).allSatisfy(value -> assertThat(value)
                    .doesNotContain("http://").doesNotContain("?").doesNotContain("secret-token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnapprovedDestinationBeforeOpeningConnection() {
        RouteSynthesisRuntimeMappingsHttpAdapter adapter = adapter(4096);

        RouteSynthesisRuntimeMappingResolution result = adapter.resolve(
                "http://169.254.169.254/latest/meta-data", "example.Controller", "run", null);

        assertThat(result.reasonCode()).isEqualTo("runtime_mappings_destination_not_allowed");
        assertThat(result.evidence()).containsExactly("mappingsDestination=blocked");
    }

    @Test
    void rejectsResponseThatExceedsConfiguredBound() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/mappings", exchange -> respond(exchange, "x".repeat(128)));
        server.start();
        try {
            RouteSynthesisRuntimeMappingResolution result = adapter(64).resolve(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/mappings",
                    "example.Controller", "run", null);

            assertThat(result.reasonCode()).isEqualTo("runtime_mappings_response_too_large");
            assertThat(result.evidence()).containsExactly("responseLimitBytes=64");
        } finally {
            server.stop(0);
        }
    }

    private RouteSynthesisRuntimeMappingsHttpAdapter adapter(int maxResponseBytes) {
        return new RouteSynthesisRuntimeMappingsHttpAdapter(
                HttpClient.newHttpClient(), new ObjectMapper(), Set.of("127.0.0.1"), maxResponseBytes);
    }

    private static void respond(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
