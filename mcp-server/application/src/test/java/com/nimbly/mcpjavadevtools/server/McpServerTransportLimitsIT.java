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

@Tag("transport")
@Timeout(90)
class McpServerTransportLimitsIT extends McpServerStdioSupport {
    @ParameterizedTest
    @CsvSource({"external,http_host_not_allowed", "scheme,http_scheme_not_allowed",
        "userinfo,http_user_info_not_allowed", "invalid,http_redirect_invalid", "loop,http_redirect_limit_exceeded"})
    void redirectsAreRevalidatedAndBounded(String mode, String reason) throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportHttpTarget destination = TransportHttpTarget.start();
                TransportHttpTarget source = TransportHttpTarget.start(exchange -> {
                    String location = switch (mode) {
                        case "external" -> destination.url("/blocked").replace("127.0.0.1", "example.invalid");
                        case "scheme" -> "file:///blocked?token=" + secret;
                        case "userinfo" -> destination.url("/blocked").replace("//", "//user:" + secret + "@");
                        case "invalid" -> "http://[invalid";
                        default -> "/loop?token=" + secret;
                    };
                    exchange.getResponseHeaders().set("Location", location);
                    TransportHttpTarget.respond(exchange, 302, "redirect");
                }); TransportScenario s = TransportScenario.start(secret)) {
            JsonNode result = s.execute(Map.of("method", "GET", "url", source.url("/redirect")));
            TransportScenario.assertBlocked(result, mode.equals("loop") ? "blocked_runtime" : "blocked_invalid", reason);
            for (int index = 0; index < (mode.equals("loop") ? 6 : 1); index++) {
                assertThat(source.received().method()).isEqualTo("GET");
            }
            source.assertNoRequest();
            destination.assertNoRequest();
            s.assertSecretsAbsent(secret);
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "redirect_rejected_" + mode);
        }
    }

    @Test
    void slowHeadersAndRedirectsShareOneDeadlineAndRecover() throws Exception {
        try (TransportHttpTarget destination = TransportHttpTarget.start(exchange -> {
            pause(350);
            TransportHttpTarget.respond(exchange, 200, "late");
        }); TransportHttpTarget source = TransportHttpTarget.start(exchange -> {
            pause(350);
            exchange.getResponseHeaders().set("Location", destination.url("/late"));
            TransportHttpTarget.respond(exchange, 302, "redirect");
        }); TransportScenario s = TransportScenario.start()) {
            JsonNode result = s.execute(Map.of("method", "GET", "url", source.url("/redirect"), "timeoutMs", 600));
            TransportScenario.assertBlocked(result, "blocked_runtime", "transport_request_timeout");
            assertThat(result.path("durationMs").asLong()).isLessThan(3_000);
            assertThat(source.received().uri()).isEqualTo("/redirect");
            assertThat(destination.received().uri()).isEqualTo("/late");
            JsonNode slow = s.execute(Map.of("method", "GET", "url", destination.url("/headers"), "timeoutMs", 100));
            TransportScenario.assertBlocked(slow, "blocked_runtime", "transport_request_timeout");
            assertThat(destination.received().uri()).isEqualTo("/headers");
            assertRecovery(s);
        }
    }

    @Test
    void unavailableTargetRedactsFailureMetadataAndDoesNotPoisonTheNextRequest() throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportScenario s = TransportScenario.start(secret)) {
            JsonNode result = s.execute(Map.of("method", "GET", "url", "http://127.0.0.1:" + freePort()
                    + "/unavailable?safe=kept&%74oken=" + secret, "timeoutMs", 500));
            TransportScenario.assertBlocked(result, "blocked_runtime", "transport_request_failed");
            TransportScenario.assertSanitized(result.toString(), secret);
            assertThat(result.path("reasonMeta").path("url").asText()).contains("safe=kept", "[REDACTED]");
            assertThat(result.path("durationMs").asLong()).isLessThan(3_000);
            assertRecovery(s);
            s.assertSecretsAbsent(secret);
        }
    }

    @Test
    void responseBodyCeilingStopsRealWorkAndRecoveryStillWorks() throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            try {
                TransportHttpTarget.respond(exchange, 200, "x".repeat(4 * 1024 * 1024 + 1));
            } catch (java.io.IOException expectedDisconnect) {
                // The bounded subscriber may close the socket before the fixture finishes writing.
            }
        }); TransportScenario s = TransportScenario.start()) {
            JsonNode result = s.execute(Map.of("method", "GET", "url", target.url("/oversized")));
            TransportScenario.assertBlocked(result, "blocked_runtime", "http_response_body_too_large");
            assertThat(result.path("bodyPreview").isNull()).isTrue();
            assertThat(target.received().uri()).isEqualTo("/oversized");
            assertRecovery(s);
        }
    }

    @Test
    void publicInputCeilingsRejectBeforeNetworkAccess() throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            for (Object body : List.of("x".repeat(262145), java.util.Collections.nCopies(6, "x".repeat(200000)))) {
                JsonNode result = s.call(TransportScenario.OPERATION, Map.of("protocol", "http", "request",
                        Map.of("method", "POST", "url", target.url("/oversized"), "body", body)), true);
                assertThat(result.path("status").asText()).isEqualTo("invalid_input");
                assertThat(result.path("reasonCode").asText()).isEqualTo("operation_input_invalid");
            }
            target.assertNoRequest();
            assertRecovery(s);
        }
    }

    private void assertRecovery(TransportScenario s) throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            JsonNode recovered = s.execute(Map.of("method", "GET", "url", target.url("/recovered")));
            assertThat(recovered.path("status").asText()).isEqualTo("pass");
            assertThat(recovered.path("bodyPreview").asText()).isEqualTo("{\"safe\":\"observed\"}");
            assertThat(target.received().uri()).isEqualTo("/recovered");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
