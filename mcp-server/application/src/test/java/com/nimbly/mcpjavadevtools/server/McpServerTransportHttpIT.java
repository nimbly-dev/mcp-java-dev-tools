package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("transport")
@Timeout(90)
class McpServerTransportHttpIT extends McpServerStdioSupport {
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void methodsReachTheTargetWithExactPathQueryHeadersAndBody(String method) throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            JsonNode result = s.execute(Map.of("method", method.toLowerCase(java.util.Locale.ROOT),
                    "url", target.url("/orders/a%20b?item=one%2Ftwo&item=three"),
                    "headers", Map.of("X-Scenario", "method-check"), "body", Map.of("quantity", 2)));
            assertThat(result.path("status").asText()).isEqualTo("pass");
            assertThat(result.path("protocol").asText()).isEqualTo("http");
            assertThat(result.path("statusCode").asInt()).isEqualTo(200);
            assertThat(result.path("headers").path("x-fixture").asText()).isEqualTo("observed");
            assertThat(result.path("bodyPreview").asText()).isEqualTo(method.equals("HEAD") ? "" : "{\"safe\":\"observed\"}");
            TransportHttpTarget.Received observed = target.received();
            assertThat(observed.method()).isEqualTo(method);
            assertThat(observed.uri()).isEqualTo("/orders/a%20b?item=one%2Ftwo&item=three");
            assertThat(observed.header("X-Scenario")).isEqualTo("method-check");
            assertThat(observed.header("Content-Type")).isEqualTo("application/json");
            assertThat(JSON.readTree(observed.body())).isEqualTo(JSON.readTree("{\"quantity\":2}"));
            target.assertNoRequest();
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "method_" + method);
        }
    }

    @ParameterizedTest
    @CsvSource({"GET,200,pass", "POST,201,pass", "DELETE,204,pass", "GET,302,pass", "GET,400,fail_http",
        "GET,401,fail_http", "GET,403,fail_http", "GET,404,fail_http", "DELETE,405,fail_http",
        "GET,429,fail_http", "GET,500,fail_http", "GET,503,fail_http"})
    void downstreamStatusesKeepTransportSemanticsAndRecovery(String method, int status, String expected) throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            int code = exchange.getRequestURI().getPath().equals("/recover") ? 200 : status;
            TransportHttpTarget.respond(exchange, code, "{\"safe\":\"response\"}");
        }); TransportScenario s = TransportScenario.start()) {
            JsonNode result = s.execute(Map.of("method", method, "url", target.url("/status")));
            assertThat(result.path("status").asText()).isEqualTo(expected);
            assertThat(result.path("statusCode").asInt()).isEqualTo(status);
            assertThat(result.path("reasonCode").isNull()).isTrue();
            assertThat(result.path("bodyPreview").asText()).isEqualTo(status == 204 ? "" : "{\"safe\":\"response\"}");
            assertThat(target.received().method()).isEqualTo(method);
            assertThat(s.execute(Map.of("method", "GET", "url", target.url("/recover"))).path("status").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/recover");
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", method + "_status_" + status);
        }
    }

    @Test
    @Tag("transport-smoke")
    void stringArrayAndAbsentBodiesPreserveContentTypeAndDefaultOptions() throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            for (Object body : List.of("plain body", List.of("one", "two"))) {
                JsonNode result = s.execute(Map.of("method", "POST", "url", target.url("/body"), "body", body,
                        "headers", Map.of("Content-Type", "application/custom")));
                assertThat(result.path("status").asText()).isEqualTo("pass");
                TransportHttpTarget.Received observed = target.received();
                assertThat(observed.header("Content-Type")).isEqualTo("application/custom");
                assertThat(observed.body()).isEqualTo(body instanceof String text ? text : JSON.writeValueAsString(body));
            }
            assertThat(s.execute(Map.of("method", "GET", "url", target.url("/empty"))).path("status").asText()).isEqualTo("pass");
            assertThat(target.received().body()).isEmpty();
            assertThat(s.result(Map.of("protocol", "http", "options", Map.of(), "request",
                    Map.of("method", "GET", "url", target.url("/maximum-timeout"), "timeoutMs", 300000)))
                    .path("status").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/maximum-timeout");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void redirectsPreserveSameOriginCredentialsAndApplyMethodRules(int status) throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/destination");
                TransportHttpTarget.respond(exchange, status, "redirect");
            } else {
                TransportHttpTarget.respond(exchange, 200, "arrived");
            }
        }); TransportScenario s = TransportScenario.start(secret)) {
            JsonNode result = s.execute(Map.of("method", "POST", "url", target.url("/redirect"), "body", "payload",
                    "headers", Map.of("Authorization", "Bearer " + secret, "Cookie", "session=" + secret,
                            "X-Api-Key", secret, "Api-Key", secret, "X-Auth-Token", secret, "X-Client-Secret", secret)));
            assertThat(result.path("status").asText()).isEqualTo("pass");
            assertThat(result.path("bodyPreview").asText()).isEqualTo("arrived");
            assertThat(target.received().uri()).isEqualTo("/redirect");
            TransportHttpTarget.Received destination = target.received();
            assertThat(destination.uri()).isEqualTo("/destination");
            assertThat(destination.method()).isEqualTo(status < 307 ? "GET" : "POST");
            assertThat(destination.body()).isEqualTo(status < 307 ? "" : "payload");
            assertThat(("Bearer " + secret).equals(destination.header("Authorization"))).as("same-origin authorization").isTrue();
            assertThat(("session=" + secret).equals(destination.header("Cookie"))).as("same-origin cookie").isTrue();
            for (String name : List.of("X-Api-Key", "Api-Key", "X-Auth-Token", "X-Client-Secret")) {
                assertThat(secret.equals(destination.header(name))).as("same-origin credential retained").isTrue();
            }
            s.assertSecretsAbsent(secret);
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "redirect_" + status);
        }
    }

    @Test
    void crossOriginRedirectStripsCredentialsBeforeTheSecondRequest() throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportHttpTarget destination = TransportHttpTarget.start();
                TransportHttpTarget source = TransportHttpTarget.start(exchange -> {
                    exchange.getResponseHeaders().set("Location", destination.url("/destination"));
                    TransportHttpTarget.respond(exchange, 307, "redirect");
                }); TransportScenario s = TransportScenario.start(secret)) {
            JsonNode result = s.execute(Map.of("method", "POST", "url", source.url("/redirect"), "body", "payload",
                    "headers", Map.of("Authorization", "Bearer " + secret, "Proxy-Authorization", "Basic " + secret,
                            "Cookie", "session=" + secret, "Set-Cookie", "session=" + secret, "X-Safe", "retained")));
            assertThat(result.path("status").asText()).isEqualTo("pass");
            assertThat(("Bearer " + secret).equals(source.received().header("Authorization"))).as("source authorization").isTrue();
            TransportHttpTarget.Received received = destination.received();
            for (String header : List.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")) {
                assertThat(received.header(header) == null).as("cross-origin credential header absent").isTrue();
            }
            assertThat(received.header("X-Safe")).isEqualTo("retained");
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.body()).isEqualTo("payload");
            s.assertSecretsAbsent(secret);
        }
    }
}
