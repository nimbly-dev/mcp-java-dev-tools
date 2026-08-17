package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpFailureEvidenceClientTest {

    @Test
    void rejectsUnsafeSidecarEndpointBeforeSending() {
        HttpFailureEvidenceClient client = client(64);

        assertThatThrownBy(() -> client.analyze(new FailureAnalyzeEvidenceRequest(
                "file:///tmp/sidecar", "trace", null, Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(FailureEvidenceClientException.class,
                        exception -> assertThat(exception.failureKind())
                                .isEqualTo(FailureEvidenceFailureKind.INVALID_ENDPOINT));
    }

    @Test
    void enforcesConfiguredResponseLimit() throws Exception {
        HttpServer server = server("12345");
        try {
            HttpFailureEvidenceClient client = client(4);

            assertThatThrownBy(() -> client.analyze(new FailureAnalyzeEvidenceRequest(
                    baseUrl(server), "trace", null, Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(FailureEvidenceClientException.class,
                            exception -> assertThat(exception.failureKind())
                                    .isEqualTo(FailureEvidenceFailureKind.RESPONSE_LIMIT_EXCEEDED));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void convertsMalformedJsonToEmptyEvidenceForActionValidation() throws Exception {
        HttpServer server = server("not-json");
        try {
            FailureEvidenceResponse response = client(64).analyze(new FailureAnalyzeEvidenceRequest(
                    baseUrl(server), "trace", null, Duration.ofSeconds(1)));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.payload()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesTransportTimeoutSeparatelyFromUnreachable() throws Exception {
        HttpServer server = server("{}", Duration.ofSeconds(2));
        try {
            assertThatThrownBy(() -> client(64).analyze(new FailureAnalyzeEvidenceRequest(
                    baseUrl(server), "trace", null, Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(FailureEvidenceClientException.class,
                            exception -> assertThat(exception.failureKind())
                                    .isEqualTo(FailureEvidenceFailureKind.TIMEOUT));
        } finally {
            server.stop(0);
        }
    }

    private HttpFailureEvidenceClient client(int maximumResponsePayloadBytes) {
        return new HttpFailureEvidenceClient(
                new ObjectMapper(), new FailureAnalysisPolicy(
                        Duration.ofSeconds(1), 200_000, maximumResponsePayloadBytes, 256, 8, 8));
    }

    private HttpServer server(String body) throws Exception {
        return server(body, Duration.ZERO);
    }

    private HttpServer server(String body, Duration delay) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__probe/failure/analyze", exchange -> {
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
