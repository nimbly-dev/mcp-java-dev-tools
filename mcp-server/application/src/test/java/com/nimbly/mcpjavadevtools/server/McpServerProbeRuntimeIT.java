package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nimbly.mcpjavadevtools.server.JvmProbeScenario.SERVICE;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** #647 real actuation, authentication, profiler and cancellation boundaries. */
@Tag("jvm-probe")
@Timeout(180)
class McpServerProbeRuntimeIT extends McpServerStdioSupport {
    private static final String UPDATE_BODY = "{\"content\":\"647 fixture update\",\"visibility\":\"PUBLIC\",\"tags\":[\"it\"]}";

    @Test
    void actuationConfirmationArmDisarmExpiryAndObservableBranch() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            String key = JvmProbeScenario.key(SERVICE, "updatePost", "if (fixtureActuationGate)");
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
            Map<String, Object> arm = Map.of("action", "arm", "sessionId", "647-actuate", "targetKey", key,
                    "returnBoolean", false, "ttlMs", 5_000, "baseUrl", s.baseUrl);
            assertThat(s.execute("probe.actuate", arm, false).path("status").asText())
                    .isEqualTo("confirmation_required");
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
            JsonNode armed = s.success("actuate", arm);
            assertThat(armed.path("targetKey").asText()).isEqualTo(key);
            assertThat(armed.path("sessionId").asText()).isEqualTo("647-actuate");
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(409);
            s.success("actuate", Map.of("action", "disarm", "sessionId", "647-actuate"));
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
            s.success("actuate", Map.of("action", "arm", "sessionId", "647-expiry", "targetKey", key,
                    "returnBoolean", false, "ttlMs", 1_000));
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(409);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            int status;
            do {
                Thread.sleep(100);
                status = s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode();
            } while (status == 409 && System.nanoTime() < deadline);
            assertThat(status).isEqualTo(200);
            assertThat(s.probe("actuate", Map.of("action", "arm", "sessionId", "invalid",
                    "targetKey", s.postKey(), "returnBoolean", true, "ttlMs", 1_000))
                    .path("reasonCode").asText()).isEqualTo("ACTUATION_FAILED");
            s.deactivate();
        }
    }

    @Test
    void authenticationRejectionsAndAuthorizedCheckAreSanitized() throws Exception {
        String observe = "647-observe-" + java.util.UUID.randomUUID();
        String actuate = "647-actuate-" + java.util.UUID.randomUUID();
        try (JvmProbeScenario s = JvmProbeScenario.start("-Dmcp.probe.auth.observe.token=" + observe,
                "-Dmcp.probe.auth.actuate.token=" + actuate)) {
            assertThat(s.result("jvm_lifecycle.attach", s.attachArguments(), true).path("reasonCode").asText())
                    .isEqualTo("active");
            assertCheckAuthentication(s, observe, actuate);
            assertThat(s.http("GET", "/api/v1/posts/101", null).statusCode()).isEqualTo(200);
            JsonNode before = authenticatedStatus(s, s.postKey(), observe);
            assertThat(before.path("probe").path("hitCount").asLong()).isPositive();
            String captureId = before.path("capturePreview").path("captureId").asText();
            assertThat(captureId).isNotEmpty();
            Map<String, Object> arm = Map.of("action", "arm", "sessionId", "647-auth",
                    "targetKey", JvmProbeScenario.key(SERVICE, "updatePost", "if (fixtureActuationGate)"),
                    "returnBoolean", false, "ttlMs", 30_000);
            assertProtectedOperations(s, arm, captureId);
            assertThat(authenticatedStatus(s, s.postKey(), observe).path("probe").path("hitCount"))
                    .isEqualTo(before.path("probe").path("hitCount"));
            assertThat(authenticatedStatus(s, s.postKey(), observe).path("runtime").path("activeSessionCount").asInt())
                    .isZero();
            JsonNode capture = sidecarJson(s, "GET", "/__probe/capture?captureId=" + captureId, null, observe);
            assertThat(capture.path("capture").path("captureId").asText()).isEqualTo(captureId);
            assertThat(sidecarJson(s, "GET", "/__probe/profiler", null, observe)
                    .path("profiler").path("status").asText()).isEqualTo("idle");
            assertThat(s.workspace.resolve("unauthorized.jfr")).doesNotExist();
            assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
            assertTokenSeparationEffects(s, arm, observe, actuate);
            assertThat(s.target.isHealthy()).isTrue();
            s.deactivate();
        }
    }

    private void assertCheckAuthentication(JvmProbeScenario s, String observe, String actuate) throws Exception {
        JsonNode absent = s.probe("check", Map.of());
        assertThat(absent.path("reasonCode").asText()).isEqualTo("DIAGNOSE_FAILED");
        assertEndpointAuthentication(absent.path("actionResult").path("reset"), false);
        assertEndpointAuthentication(absent.path("actionResult").path("status"), false);
        for (String token : List.of(observe, actuate)) {
            JsonNode check = s.probe("check", Map.of("http", Map.of("headers",
                    Map.of("authorization", "Bearer " + token))));
            assertThat(check.path("reasonCode").asText()).isEqualTo("DIAGNOSE_FAILED");
            assertEndpointAuthentication(check.path("actionResult").path("reset"), token.equals(actuate));
            assertEndpointAuthentication(check.path("actionResult").path("status"), token.equals(observe));
            assertThat(check.toString()).doesNotContain(observe, actuate, "Bearer ");
        }
    }

    private void assertEndpointAuthentication(JsonNode endpoint, boolean allowed) {
        assertThat(endpoint.path("status").asText()).isEqualTo(allowed ? "AVAILABLE" : "UNAUTHORIZED");
        assertThat(endpoint.path("httpStatus").asInt()).isEqualTo(allowed ? 200 : 401);
    }

    private void assertProtectedOperations(JvmProbeScenario s, Map<String, Object> arm, String captureId) throws Exception {
        Map<String, Map<String, Object>> requests = Map.of(
                "status", Map.of("key", s.postKey()), "reset", Map.of("key", s.postKey()),
                "capture", Map.of("captureId", captureId), "actuate", arm,
                "profiler", Map.of("action", "start", "provider", "jfr", "sessionId", "647-unauthorized",
                        "outputPath", s.workspace.resolve("unauthorized.jfr").toString()));
        for (var request : requests.entrySet()) {
            JsonNode rejected = s.probe(request.getKey(), request.getValue());
            String reason = request.getKey().equals("actuate") ? "ACTUATION_FAILED"
                    : request.getKey().toUpperCase(java.util.Locale.ROOT) + "_FAILED";
            assertThat(rejected.path("reasonCode").asText()).isEqualTo(reason);
            JsonNode result = rejected.path("actionResult");
            if (request.getKey().equals("status") || request.getKey().equals("reset")) {
                assertThat(result.path("entries")).hasSize(1);
                assertThat(result.path("entries").get(0).path("httpStatus").asInt()).isEqualTo(401);
            } else {
                String field = request.getKey().equals("profiler") ? "detail" : "reason";
                assertThat(result.path(field).asText()).isEqualTo("unauthorized");
            }
            assertThat(rejected.toString()).doesNotContain("647-observe-", "647-actuate-", "Bearer ");
        }
    }

    private void assertTokenSeparationEffects(JvmProbeScenario s, Map<String, Object> arm,
            String observe, String actuate) throws Exception {
        assertDeniedSidecar(s, "POST", "/__probe/actuate", arm, observe, "actuate");
        assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
        sidecarJson(s, "POST", "/__probe/actuate", arm, actuate);
        assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(409);
        Map<String, Object> disarm = Map.of("action", "disarm", "sessionId", "647-auth");
        assertDeniedSidecar(s, "POST", "/__probe/actuate", disarm, observe, "actuate");
        assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(409);
        sidecarJson(s, "POST", "/__probe/actuate", disarm, actuate);
        assertThat(s.http("PUT", "/api/v1/posts/101", UPDATE_BODY).statusCode()).isEqualTo(200);
        String statusPath = "/__probe/status?key=" + java.net.URLEncoder.encode(s.postKey(), java.nio.charset.StandardCharsets.UTF_8);
        assertDeniedSidecar(s, "GET", statusPath, null, actuate, "observe");
        assertDeniedSidecar(s, "POST", "/__probe/reset", Map.of("key", s.postKey()), observe, "actuate");
        assertThat(authenticatedStatus(s, s.postKey(), observe).path("probe").path("hitCount").asLong()).isPositive();
        sidecarJson(s, "POST", "/__probe/reset", Map.of("key", s.postKey()), actuate);
        assertThat(authenticatedStatus(s, s.postKey(), observe).path("probe").path("hitCount").asLong()).isZero();
    }

    private JsonNode authenticatedStatus(JvmProbeScenario s, String key, String token) throws Exception {
        return sidecarJson(s, "GET", "/__probe/status?key=" + java.net.URLEncoder.encode(key,
                java.nio.charset.StandardCharsets.UTF_8), null, token);
    }

    private JsonNode sidecarJson(JvmProbeScenario s, String method, String path, Object body, String token) throws Exception {
        var response = sidecarRequest(s, method, path, body, token);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private void assertDeniedSidecar(JvmProbeScenario s, String method, String path, Object body,
            String token, String scope) throws Exception {
        var response = sidecarRequest(s, method, path, body, token);
        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.path("error").asText()).isEqualTo("unauthorized");
        assertThat(error.path("scope").asText()).isEqualTo(scope);
        assertThat(response.body()).doesNotContain(token);
    }

    private java.net.http.HttpResponse<String> sidecarRequest(JvmProbeScenario s, String method,
            String path, Object body, String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(s.baseUrl + path))
                .timeout(Duration.ofSeconds(5)).header("authorization", "Bearer " + token)
                .header("content-type", "application/json").method(method, body == null
                        ? java.net.http.HttpRequest.BodyPublishers.noBody()
                        : java.net.http.HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test
    void realJfrLifecycleDownloadSessionMismatchAndReset() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            Path output = s.workspace.resolve("owned.jfr");
            Map<String, Object> start = Map.of("action", "start", "provider", "jfr", "sessionId", "647-jfr",
                    "outputPath", output.toString(), "outputFormat", "jfr", "event", "cpu", "intervalNanos", 1_000_000);
            assertThat(s.execute("probe.profiler", merge(start, "baseUrl", s.baseUrl), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            JsonNode running = s.success("profiler", start);
            assertThat(running.path("status").asText()).isEqualTo("running");
            assertThat(running.path("provider").asText()).isEqualTo("jfr");
            assertThat(s.success("profiler", Map.of("action", "status")).path("sessionId").asText())
                    .isEqualTo("647-jfr");
            assertThat(s.success("profiler", Map.of("action", "start", "sessionId", "other"))
                    .path("detail").asText()).isEqualTo("profiler_session_already_running");
            assertThat(s.success("profiler", Map.of("action", "reset")).path("detail").asText())
                    .isEqualTo("profiler_running_reset_denied");
            assertThat(s.success("profiler", Map.of("action", "stop", "sessionId", "wrong"))
                    .path("detail").asText()).isEqualTo("profiler_session_mismatch");
            for (int request = 0; request < 20; request++) {
                assertThat(s.http("GET", "/api/v1/posts/101", null).statusCode()).isEqualTo(200);
            }
            JsonNode stopped = s.success("profiler", Map.of("action", "stop", "sessionId", "647-jfr"));
            assertThat(stopped.path("status").asText()).isEqualTo("completed");
            assertJfrDownload(s, output);
            s.success("profiler", Map.of("action", "reset"));
            assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("idle");
            assertThat(s.probe("profiler", Map.of("action", "download", "outputPath", s.workspace.resolve("absent.jfr").toString()))
                    .path("reasonCode").asText()).isEqualTo("PROFILER_FAILED");
            s.deactivate();
        }
    }

    @Test
    void profilerAutoDefaultsAndPlatformProviderPolicy() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            JsonNode running = s.success("profiler", Map.of("action", "start", "sessionId", "647-auto"));
            assertThat(running.path("status").asText()).isEqualTo("running");
            assertThat(running.path("supported").asBoolean()).isTrue();
            assertThat(running.path("provider").asText()).isIn("jfr", "async-profiler");
            profileFixtureTraffic(s);
            JsonNode stopped = s.success("profiler", Map.of("action", "stop", "sessionId", "647-auto"));
            assertThat(stopped.path("provider")).isEqualTo(running.path("provider"));
            assertProfilerRecording(s, stopped, "647-auto");
            s.success("profiler", Map.of("action", "reset"));
            s.deactivate();
        }
    }

    @Test
    void nativeAsyncProfilerProducesOwnedRecordingOrExplicitPlatformRejection() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            JsonNode async = s.probe("profiler", Map.of("action", "start", "provider", "async-profiler",
                    "sessionId", "647-async", "event", "wall", "intervalNanos", 1_000_000,
                    "outputPath", s.workspace.resolve("native.jfr").toString(), "outputFormat", "jfr"));
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                assertThat(async.path("reasonCode").asText()).isEqualTo("PROFILER_FAILED");
                assertThat(async.path("actionResult").path("detail").asText()).isEqualTo("profiler_unsupported_platform");
                assertThat(s.workspace.resolve("native.jfr")).doesNotExist();
            } else {
                assertThat(async.path("reasonCode").asText()).isEqualTo("SUCCESS");
                JsonNode running = async.path("actionResult");
                assertThat(running.path("status").asText()).isEqualTo("running");
                assertThat(running.path("supported").asBoolean()).isTrue();
                assertThat(running.path("provider").asText()).isEqualTo("async-profiler");
                assertThat(running.path("sessionId").asText()).isEqualTo("647-async");
                assertThat(running.path("event").asText()).isEqualTo("wall");
                JsonNode status = s.success("profiler", Map.of("action", "status"));
                assertThat(status.path("status").asText()).isEqualTo("running");
                assertThat(status.path("sessionId").asText()).isEqualTo("647-async");
                profileFixtureTraffic(s);
                JsonNode stopped = s.success("profiler", Map.of("action", "stop", "sessionId", "647-async"));
                assertThat(stopped.path("provider").asText()).isEqualTo("async-profiler");
                assertProfilerRecording(s, stopped, "647-async");
                s.success("profiler", Map.of("action", "reset"));
                assertThat(s.success("profiler", Map.of("action", "status")).path("status").asText()).isEqualTo("idle");
            }
            s.deactivate();
        }
    }

    private void profileFixtureTraffic(JvmProbeScenario s) throws Exception {
        for (int request = 0; request < 40; request++) {
            assertThat(s.http("GET", "/api/v1/posts/101", null).statusCode()).isEqualTo(200);
            Thread.sleep(25);
        }
    }

    private void assertProfilerRecording(JvmProbeScenario s, JsonNode stopped, String session) throws Exception {
        assertThat(stopped.path("status").asText()).isEqualTo("completed");
        assertThat(stopped.path("supported").asBoolean()).isTrue();
        assertThat(stopped.path("sessionId").asText()).isEqualTo(session);
        Path output = Path.of(stopped.path("outputPath").asText()).toAbsolutePath().normalize();
        assertThat(output.startsWith(s.workspace)).isTrue();
        assertThat(output).isRegularFile();
        assertThat(Files.size(output)).isPositive();
        var events = RecordingFile.readAllEvents(output);
        assertThat(events).isNotEmpty();
        if (stopped.path("provider").asText().equals("async-profiler")) {
            var samples = events.stream().filter(event -> event.getEventType().getName().endsWith("ExecutionSample")
                    || event.getEventType().getName().endsWith("WallClockSample")).toList();
            assertThat(samples).isNotEmpty();
            assertThat(samples).anySatisfy(event -> {
                assertThat(event.getStackTrace()).isNotNull();
                assertThat(event.getStackTrace().getFrames()).isNotEmpty();
            });
            JSON.writerWithDefaultPrettyPrinter().writeValue(s.workspace.resolve("native-recording-evidence.json").toFile(),
                    Map.of("provider", "async-profiler", "sessionId", session, "eventCount", events.size(),
                            "sampleCount", samples.size(), "outputBytes", Files.size(output)));
        }
        Path download = s.workspace.resolve(session + "-download.jfr");
        JsonNode downloaded = s.success("profiler", Map.of("action", "download", "sessionId", session,
                "outputPath", download.toString(), "outputFormat", "jfr"));
        assertThat(downloaded.path("downloadedBytes").asLong()).isEqualTo(Files.size(output));
        assertThat(Files.mismatch(output, download)).isEqualTo(-1);
    }
    @Test
    void cancellationAndShutdownDuringWaitAllowOwnedRuntimeRecovery() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            String key = s.postKey();
            s.success("reset", Map.of("key", key));
            try (ProbeRequestObserver observer = new ProbeRequestObserver(s.baseUrl)) {
                s.server.send(executeRequest(700, "probe.wait_for_hit", Map.of("baseUrl", observer.baseUrl(), "key", key,
                        "timeoutMs", 30_000, "pollIntervalMs", 100, "maxRetries", 1), false));
                observer.awaitRequests(2);
                s.server.send(request(701, "tools/list", Map.of()));
                assertThat(s.server.responseFor(701).path("result").path("tools")).hasSize(3);
                s.server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                        "params", Map.of("requestId", 700, "reason", "owned JVM probe IT")));
                observer.assertPollingStopped();
                s.hit(key, "/api/v1/posts/101");
                int baseline = observer.requests.get();
                s.success("reset", Map.of("key", key));
                s.server.send(executeRequest(702, "probe.wait_for_hit", Map.of("baseUrl", observer.baseUrl(), "key", key,
                        "timeoutMs", 30_000, "pollIntervalMs", 100, "maxRetries", 1), false));
                observer.awaitRequests(baseline + 2);
                s.server.closeInputAndAwaitTermination();
                observer.assertPollingStopped();
                assertThat(s.server.hasResponseFor(700)).isFalse();
                assertThat(s.target.isHealthy()).isTrue();
                try (McpServerProcess recovery = JvmProbeScenario.startServer(s.workspace)) {
                    initialize(recovery);
                    recovery.send(executeRequest(703, "jvm_lifecycle.deactivate", s.identity(), true));
                    JsonNode result = toolPayload(recovery.responseFor(703, Duration.ofSeconds(90))).path("result");
                    assertThat(result.path("reasonCode").asText()).isEqualTo("deactivated");
                }
                assertThat(s.target.awaitSidecarReachability(s.probePort, false, Duration.ofSeconds(10))).isTrue();
                assertThat(s.target.isHealthy()).isTrue();
            }
        }
    }

    private Map<String, Object> merge(Map<String, Object> input, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        result.put(key, value);
        return result;
    }

    private void assertJfrDownload(JvmProbeScenario s, Path output) throws Exception {
        assertThat(output).isRegularFile();
        assertThat(Files.size(output)).isPositive();
        List<jdk.jfr.consumer.RecordedEvent> events = RecordingFile.readAllEvents(output);
        assertThat(events).isNotEmpty();
        assertThat(events).anySatisfy(event -> assertThat(event.getEventType().getName()).startsWith("jdk."));
        Path download = s.workspace.resolve("download.jfr");
        assertThat(s.probe("profiler", Map.of("action", "download", "sessionId", "wrong",
                "outputPath", download.toString())).path("reasonCode").asText()).isEqualTo("PROFILER_FAILED");
        assertThat(download).doesNotExist();
        JsonNode downloaded = s.success("profiler", Map.of("action", "download", "sessionId", "647-jfr",
                "outputPath", download.toString(), "outputFormat", "jfr"));
        assertThat(downloaded.path("downloadedBytes").asLong()).isEqualTo(Files.size(output));
        assertThat(Files.mismatch(output, download)).isEqualTo(-1);
    }
}
