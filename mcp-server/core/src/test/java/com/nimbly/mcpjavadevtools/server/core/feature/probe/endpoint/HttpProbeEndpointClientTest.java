package com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpProbeEndpointClientTest {

    @Test
    void returnsAValidatedBoundedResponseFromTheSidecarEndpointContract() throws Exception {
        HttpServer server = server("{}", "X-Request-Id", "request-1");
        try {
            ProbeEndpointResponse response = new HttpProbeEndpointClient().exchange(request(server, configuration(64)));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.payload()).isEqualTo("{}");
            assertThat(response.headers()).containsEntry("x-request-id", "request-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAResponseBodyThatExceedsTheCoreConfiguredLimit() throws Exception {
        HttpServer server = server("12345", "Content-Type", "application/json");
        try {
            assertThatThrownBy(() -> new HttpProbeEndpointClient().exchange(request(server, configuration(4))))
                    .isInstanceOf(ProbeEndpointClientException.class)
                    .hasMessage("Probe endpoint response exceeded the configured limit");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(String body, String headerName, String headerValue) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__probe/status", exchange -> {
            exchange.getResponseHeaders().add(headerName, headerValue);
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ProbeEndpointRequest request(HttpServer server, ProbeEndpointConfiguration configuration) {
        return new ProbeEndpointRequest(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/__probe/status"),
                "GET",
                Map.of(),
                "",
                Duration.ofSeconds(1),
                configuration);
    }

    private ProbeEndpointConfiguration configuration(int maximumResponsePayloadBytes) {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10);
        return new ProbeEndpointConfiguration(
                null,
                new ProbeEndpointPaths(
                        "/__probe/status",
                        "/__probe/reset",
                        "/__probe/actuate",
                        "/__probe/capture",
                        "/__probe/profiler"),
                new ProbeRequestPolicy(Duration.ofSeconds(15), Duration.ofMillis(500), 1, false, 3, bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, maximumResponsePayloadBytes));
    }
}
