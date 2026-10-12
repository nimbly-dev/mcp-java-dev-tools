package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("failure-analysis")
@Timeout(180)
class McpServerFailureAnalysisRuntimeIT extends McpServerStdioSupport {
    @Test
    void realAuthenticationRedactionUnavailableRuntimeAndRecovery() throws Exception {
        String token = "649-observe-" + java.util.UUID.randomUUID();
        String secret = "649-password-sentinel";
        try (FailureAnalysisScenario s = FailureAnalysisScenario.start()) {
            s.attachAndRecord();
            Map<String, Object> analysis = s.analysis("hands_off");
            analysis.put("trace", s.trace.replaceFirst(": .*\n", ": password=" + secret + "\n"));
            JsonNode sanitized = s.result("analyze_trace", analysis);
            assertThat(sanitized.path("outcome").asText()).isEqualTo("ANALYZED");
            assertThat(sanitized.path("fingerprint").path("normalizedMessage").asText()).isEqualTo("<redacted>");
            Path persisted = s.runtime.workspace.resolve("sanitized-investigation.json");
            JSON.writeValue(persisted.toFile(), sanitized);
            assertThat(Files.readString(persisted)).doesNotContain(secret, token);
            assertProtectedRuntime(s, token);
            for (String action : List.of("analyze_trace", "verify_reproduction")) {
                Map<String, Object> input = action.equals("analyze_trace") ? s.analysis(null) : s.verification();
                for (String base : List.of("http://127.0.0.1:" + freePort(), "ftp://127.0.0.1",
                        "http://password=" + secret + "@127.0.0.1")) {
                    input.put("sidecarBaseUrl", base);
                    assertBlocked(s.result(action, input), action);
                }
            }
            assertThat(s.result("analyze_trace", s.analysis(null)).path("outcome").asText()).isEqualTo("ANALYZED");
            s.cleanup();
            assertThat(String.join("\n", s.runtime.server.stdoutLines())).doesNotContain(secret, token);
            assertThat(s.runtime.server.stderrText()).doesNotContain(secret, token);
            JavaItScenario.observe("failure_analysis.analyze_trace", "ASSERTED", "exception_payload_auth_persistence_diagnostics_redacted");
        }
    }

    private void assertProtectedRuntime(FailureAnalysisScenario s, String token) throws Exception {
        try (JvmProbeScenario protectedRuntime = JvmProbeScenario.start("-Dmcp.probe.auth.observe.token=" + token)) {
            // The shared unauthenticated health check deliberately rejects a protected Sidecar.
            assertThat(protectedRuntime.result("jvm_lifecycle.attach", protectedRuntime.attachArguments(), true)
                    .path("reasonCode").asText()).isEqualTo("active");
            assertThat(protectedRuntime.http("DELETE", FailureAnalysisScenario.PATH, null).statusCode()).isEqualTo(500);
            assertThat(protectedRuntime.probe("status", Map.of("key", s.key)).path("reasonCode").asText())
                    .isEqualTo("STATUS_FAILED");
            // Authenticated status/capture headers are absent from the current Probe CDE schema.
            // Observe real Sidecar evidence here; do not claim a protected Probe Skill workflow.
            JsonNode status = authenticatedStatus(protectedRuntime, s.key, token);
            JsonNode line = status.path("probe");
            assertThat(line.path("hitCount").asLong()).isPositive();
            String capture = status.path("capturePreview").path("captureId").asText();
            assertThat(capture).isNotBlank();
            for (String action : List.of("analyze_trace", "verify_reproduction")) {
                Map<String, Object> input = action.equals("analyze_trace") ? s.analysis("guided") : s.verification();
                input.put("sidecarBaseUrl", protectedRuntime.baseUrl);
                if (action.equals("verify_reproduction")) {
                    input.put("captureId", capture);
                    input.put("lineHit", Map.of("strictLineKey", s.key, "hitCount", line.path("hitCount").asLong()));
                }
                for (String authorization : List.of("absent", "Bearer wrong-credential-sentinel")) {
                    if (!authorization.equals("absent")) {
                        input.put("sidecarAuthorization", authorization);
                    }
                    JsonNode denied = s.result(action, input);
                    assertBlocked(denied, action);
                    assertThat(denied.path("httpStatus").asInt()).isEqualTo(401);
                }
                input.put("sidecarAuthorization", "Bearer " + token);
                JsonNode authorized = s.result(action, input);
                assertThat(authorized.path("outcome").asText()).isEqualTo(action.equals("analyze_trace") ? "ANALYZED" : "REPRODUCED");
                assertThat(authorized.toString()).doesNotContain(token, "wrong-credential-sentinel");
            }
            protectedRuntime.deactivate();
        }
    }

    private JsonNode authenticatedStatus(JvmProbeScenario runtime, String key, String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(runtime.baseUrl
                + "/__probe/status?key=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5)).header("authorization", "Bearer " + token).GET().build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"analyze_trace", "verify_reproduction"})
    void streamingBodyTimeoutClosesConnectionAndRecovers(String action) throws Exception {
        try (McpServerProcess server = JvmProbeScenario.startServer(JavaItScenario.workspace());
                DelayedHttpTarget target = DelayedHttpTarget.start()) {
            initialize(server);
            Map<String, Object> input = delayedInput(action, target.url());
            input.put("timeoutMs", 1_000);
            long start = System.nanoTime();
            server.send(executeRequest(700, "failure_analysis." + action, input, false));
            JsonNode payload = toolPayload(server.responseFor(700, Duration.ofSeconds(8)));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(payload.path("status").asText()).isEqualTo("succeeded");
            assertBlocked(payload.path("result"), action);
            target.awaitRequest();
            target.awaitClientDisconnect();
            assertRecovery(server);
            JavaItScenario.observe("failure_analysis." + action, "ASSERTED", "streaming_body_deadline_connection_closed_recovered");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"analyze_trace", "verify_reproduction"})
    void cancellationClosesReadHasNoLateResponseAndRecovers(String action) throws Exception {
        try (McpServerProcess server = JvmProbeScenario.startServer(JavaItScenario.workspace());
                DelayedHttpTarget target = DelayedHttpTarget.start()) {
            initialize(server);
            server.send(executeRequest(700, "failure_analysis." + action, delayedInput(action, target.url()), false));
            target.awaitRequest();
            target.assertStillConnected();
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                    "params", Map.of("requestId", 700, "reason", "owned failure analysis cancellation")));
            target.awaitClientDisconnect();
            assertRecovery(server);
            server.closeInputAndAwaitTermination();
            assertThat(server.hasResponseFor(700)).isFalse();
            JavaItScenario.observe("failure_analysis." + action, "ASSERTED", "cancelled_read_closed_no_late_response_recovered");
        }
    }

    private Map<String, Object> delayedInput(String action, String url) {
        if (action.equals("analyze_trace")) {
            return new LinkedHashMap<>(Map.of("trace", "negative transport case; never claimed as a real trace",
                    "sidecarBaseUrl", url));
        }
        return new LinkedHashMap<>(Map.of("captureId", "negative-transport-case-no-capture-fabricated",
                "expectedFingerprint", FailureAnalysisScenario.expected(), "sidecarBaseUrl", url,
                "lineHit", Map.of("strictLineKey", "example.Service#method:1", "hitCount", 1)));
    }

    @Test
    void malformedRejectedAndOversizedHttpEvidenceNeverClaimsDiagnosis() throws Exception {
        var target = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var redirects = new java.util.concurrent.atomic.AtomicInteger();
        String base = "http://127.0.0.1:" + target.getAddress().getPort();
        target.createContext("/", exchange -> {
            try (exchange) {
                String mode = exchange.getRequestURI().getPath().split("/")[1];
                if (mode.equals("redirected")) {
                    redirects.incrementAndGet();
                }
                int status = switch (mode) {
                    case "forbidden" -> 403;
                    case "error" -> 500;
                    default -> 200;
                };
                if (mode.equals("redirect")) {
                    status = 302;
                    exchange.getResponseHeaders().set("Location", base + "/redirected");
                }
                String body = faultBody(mode);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException disconnected) {
                // Oversized body rejection may close the stream before the target finishes writing.
            }
        });
        target.start();
        try (McpServerProcess server = JvmProbeScenario.startServer(JavaItScenario.workspace())) {
            initialize(server);
            assertFaultResponses(server, base);
            assertThat(redirects.get()).as("Failure credentials must never follow redirects").isZero();
        } finally {
            target.stop(0);
        }
    }

    private String faultBody(String mode) {
        return switch (mode) {
            case "empty" -> "";
            case "malformed" -> "{password=649-fault-secret-sentinel";
            case "array" -> "[]";
            case "oversized" -> " ".repeat(1_048_577);
            default -> "{}";
        };
    }

    private void assertFaultResponses(McpServerProcess server, String base) throws Exception {
        int id = 100;
        for (String action : List.of("analyze_trace", "verify_reproduction")) {
            for (String mode : List.of("empty", "malformed", "array", "missing", "oversized", "forbidden", "error", "redirect")) {
                Map<String, Object> input = delayedInput(action, base + "/" + mode);
                input.put("sidecarAuthorization", "Bearer 649-fault-secret-sentinel");
                server.send(executeRequest(id, "failure_analysis." + action, input, false));
                JsonNode payload = toolPayload(server.responseFor(id++));
                assertThat(payload.path("status").asText()).isEqualTo("succeeded");
                if (mode.equals("missing")) {
                    McpServerFailureAnalysisIT.assertNoDiagnosis(payload.path("result"), "INCONCLUSIVE",
                            action.equals("analyze_trace") ? "failure_fingerprint_incomplete" : "failure_verification_invalid");
                } else {
                    assertBlocked(payload.path("result"), action);
                }
                assertThat(payload.toString()).doesNotContain("649-fault-secret-sentinel");
                assertRecovery(server);
                JavaItScenario.observe("failure_analysis." + action, "ASSERTED", "negative_http_evidence_" + mode);
            }
        }
    }

    private void assertRecovery(McpServerProcess server) throws Exception {
        server.send(executeRequest(701, "failure_analysis.verify_reproduction",
                McpServerFailureAnalysisValidationIT.terminal("CANCELLED", "cleanup_confirmed", 0), false));
        JsonNode payload = toolPayload(server.responseFor(701));
        assertThat(payload.path("status").asText()).isEqualTo("succeeded");
        McpServerFailureAnalysisIT.assertNoDiagnosis(payload.path("result"), "CANCELLED", "bounded_investigation_ended");
    }

    private void assertBlocked(JsonNode result, String action) {
        assertThat(result.path("outcome").asText()).isEqualTo("BLOCKED_SIDECAR_UNAVAILABLE");
        assertThat(result.path("reasonCode").asText()).isEqualTo(action.equals("analyze_trace")
                ? "sidecar_failure_analysis_unavailable" : "sidecar_failure_verification_unavailable");
        assertThat(result.path("diagnosisClaimed").asBoolean()).isFalse();
    }
}
