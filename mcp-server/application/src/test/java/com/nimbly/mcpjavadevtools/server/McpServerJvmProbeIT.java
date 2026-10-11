package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nimbly.mcpjavadevtools.server.JvmProbeScenario.CONTROLLER;
import static com.nimbly.mcpjavadevtools.server.JvmProbeScenario.SERVICE;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #647 real lifecycle, registry, strict Probe, capture and recovery acceptance. */
@Tag("jvm-probe")
@Timeout(180)
class McpServerJvmProbeIT extends McpServerStdioSupport {

    @Test
    void ownedIdentityConfirmationDuplicateAttachAndRepeatedDeactivation() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            JsonNode jvms = s.result("jvm_lifecycle.list_jvms", Map.of(), false)
                    .path("actionResult").path("jvms");
            JsonNode owned = java.util.stream.StreamSupport.stream(jvms.spliterator(), false)
                    .filter(jvm -> jvm.path("pid").asText().equals(Long.toString(s.target.pid())))
                    .findFirst().orElseThrow();
            assertThat(owned.path("processStartEpochMs").asLong()).isEqualTo(s.target.processStartEpochMs());
            assertThat(s.execute("jvm_lifecycle.attach", s.attachArguments(), false).path("status").asText())
                    .isEqualTo("confirmation_required");
            assertThat(s.target.awaitSidecarReachability(s.probePort, false, Duration.ofSeconds(1))).isTrue();
            Map<String, Object> stale = new LinkedHashMap<>(s.attachArguments());
            stale.put("expectedProcessStartEpochMs", s.target.processStartEpochMs() - 10_000);
            assertThat(s.result("jvm_lifecycle.attach", stale, true).path("reasonCode").asText())
                    .isEqualTo("pid_reused_or_process_identity_mismatch");
            assertThat(s.result("jvm_lifecycle.attach", Map.of("pid", "2147483647",
                    "expectedProcessStartEpochMs", 1), true).path("reasonCode").asText())
                    .isEqualTo("pid_not_live");
            assertLifecycleSafety(s);
            for (int cycle = 0; cycle < 2; cycle++) {
                s.attach();
                assertThat(s.result("jvm_lifecycle.attach", s.attachArguments(), true)
                        .path("reasonCode").asText()).isEqualTo("already_active");
                s.hit(s.postKey(), "/api/v1/posts/101");
                assertThat(s.execute("jvm_lifecycle.deactivate", s.identity(), false).path("status").asText())
                        .isEqualTo("confirmation_required");
                s.deactivate();
                assertBoundedTargetChildren(s);
                try (var reports = Files.list(s.workspace.resolve("tmp"))) {
                    assertThat(reports.filter(path -> path.getFileName().toString().startsWith("mcp-jvm-lifecycle-")))
                            .as("Completed helper calls must remove owned lifecycle reports").isEmpty();
                }
                assertThat(s.probe("check", Map.of("timeoutMs", 200)).path("reasonCode").asText())
                        .isEqualTo("DIAGNOSE_FAILED");
            }
        }
    }

    @Test
    void registryStrictSingleBatchCaptureTimeoutAndRouting() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            assertRegistry(s);
            String key = s.postKey();
            String other = JvmProbeScenario.key(SERVICE, "getPost", "return toDetail(requirePost(postId));");
            s.hit(key, "/api/v1/posts/101");
            assertCapture(s, key);
            assertThat(s.execute("probe.reset", Map.of("key", key, "baseUrl", s.baseUrl), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            assertThat(s.status(key).path("hitCount").asLong()).isPositive();
            assertBatchAndClassResetEffects(s, key, other);
            long start = System.nanoTime();
            assertThat(s.probe("wait_for_hit", Map.of("key", key, "timeoutMs", 200,
                    "pollIntervalMs", 25, "maxRetries", 1)).path("reasonCode").asText()).isEqualTo("WAIT_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            String declaration = JvmProbeScenario.key(CONTROLLER, "getPost", "public PostDetailResponse getPost(");
            assertThat(s.probe("status", Map.of("key", declaration)).path("reasonCode").asText())
                    .isEqualTo("INVALID_LINE_TARGET");
            assertThat(s.probe("wait_for_hit", Map.of("key", declaration, "timeoutMs", 200))
                    .path("reasonCode").asText()).isEqualTo("INVALID_LINE_TARGET");
            assertThat(s.probe("status", Map.of("keys", List.of(key, "invalid")))
                    .path("actionResult").path("entries").get(1).path("lineValidation").asText())
                    .isEqualTo("line_key_required");
            assertThat(s.probe("reset", Map.of("keys", List.of(key, "invalid")))
                    .path("reasonCode").asText()).isEqualTo("RESET_FAILED");
            s.deactivate();
        }
    }

    @Test
    @Tag("jvm-smoke")
    void lambdaMappedSourceLineHasActualOwningMethodHits() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            String key = JvmProbeScenario.key(SERVICE, "getFeed",
                    ".filter(post -> !Objects.equals(post.authorUsername(), username))");
            s.hit(key, "/api/v1/feed?page=0&size=5");
            assertThat(s.status(key).path("key").asText()).isEqualTo(key);
            s.deactivate();
        }
    }

    @Test
    void captureExecutionPathOptInHonorsConfiguredPayloadBounds() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            s.hit(s.postKey(), "/api/v1/posts/101");
            String captureId = s.status(s.postKey()).path("capturePreview").path("captureId").asText();
            assertThat(captureId).isNotEmpty();
            try (McpServerProcess compact = JvmProbeScenario.startServer(s.workspace,
                    "--mcpjvm.probe.capture.response.include-execution-paths=true",
                    "--mcpjvm.probe.capture.response.max-string-length=32",
                    "--mcpjvm.probe.capture.response.max-execution-paths=1")) {
                initialize(compact);
                compact.send(executeRequest(850, "probe.capture",
                        Map.of("baseUrl", s.baseUrl, "captureId", captureId), false));
                JsonNode payload = toolPayload(compact.responseFor(850));
                assertThat(payload.path("status").asText()).isEqualTo("succeeded");
                assertThat(payload.path("result").path("reasonCode").asText()).isEqualTo("SUCCESS");
                JsonNode capture = payload.path("result").path("actionResult").path("capture");
                assertThat(capture.path("captureId").asText()).isEqualTo(captureId);
                assertThat(capture.path("methodKey").asText()).hasSizeLessThanOrEqualTo(32);
                assertThat(capture.path("executionPaths")).hasSize(1);
                assertThat(capture.path("executionPaths").get(0).asText()).hasSizeLessThanOrEqualTo(32);
                assertThat(capture.has("args")).isFalse();
                assertThat(capture.has("returnValue")).isFalse();
                JavaItScenario.observe("probe.capture", "SUCCESS", "execution_paths_opt_in_bounded");
            }
            s.deactivate();
        }
    }

    @Test
    void partialAttachPortConflictCleansUpAndCanRecover() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            try (ServerSocket occupied = new ServerSocket(s.probePort)) {
                JsonNode failed = s.result("jvm_lifecycle.attach", s.attachArguments(), true);
                assertThat(failed.path("reasonCode").asText()).as(failed.toPrettyString())
                        .isEqualTo("probe_server_start_failed");
                assertThat(s.target.isHealthy()).isTrue();
            }
            s.attach();
            s.hit(s.postKey(), "/api/v1/posts/101");
            s.deactivate();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.example.social.post.app.**", "com.example.social.post.app.**,com.example.social.post.api.**",
            "com.example.social.post.app.**,com.example.social.post.api.model.PostCreateRequest"})
    void additionalPackageAndExactClassIncludeScope(String include) throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            Map<String, Object> arguments = new LinkedHashMap<>(s.attachArguments());
            arguments.put("include", include);
            assertThat(s.result("jvm_lifecycle.attach", arguments, true).path("reasonCode").asText())
                    .isEqualTo("active");
            assertThat(s.http("POST", "/api/v1/posts",
                    "{\"content\":\"647 include scope\",\"visibility\":\"PUBLIC\",\"tags\":[\"it\"]}")
                    .statusCode()).isEqualTo(201);
            assertThat(s.target.wasInstrumented("com.example.social.post.api.model.PostCreateRequest"))
                    .isEqualTo(include.contains(".api."));
            s.hit(s.postKey(), "/api/v1/posts/101");
            s.deactivate();
        }
    }

    @Test
    void concurrentAttachAndExcludedClassPreserveRuntimeOwnership() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            Map<String, Object> arguments = new LinkedHashMap<>(s.attachArguments());
            arguments.put("exclude", SERVICE);
            s.server.send(executeRequest(800, "jvm_lifecycle.attach", arguments, true));
            s.server.send(executeRequest(801, "jvm_lifecycle.attach", arguments, true));
            List<String> outcomes = List.of(
                    toolPayload(s.server.responseFor(800, Duration.ofSeconds(90))).path("result").path("reasonCode").asText(),
                    toolPayload(s.server.responseFor(801, Duration.ofSeconds(90))).path("result").path("reasonCode").asText());
            assertThat(outcomes).containsExactlyInAnyOrder("active", "already_active");
            s.hit(s.postKey(), "/api/v1/posts/101");
            assertThat(s.target.wasInstrumented(SERVICE)).isFalse();
            assertThat(s.status(s.postKey()).path("runtime").path("activeSessionCount").asInt()).isZero();
            assertThat(s.target.isAlive()).isTrue();
            s.deactivate();
        }
    }

    private void assertBoundedTargetChildren(JvmProbeScenario s) throws Exception {
        ProcessHandle target = ProcessHandle.of(s.target.pid()).orElseThrow();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        List<ProcessHandle> children;
        do {
            try (var descendants = target.descendants()) {
                List<ProcessHandle> live = descendants.filter(ProcessHandle::isAlive).toList();
                assertThat(live.stream().filter(this::isWindowsConsoleHost))
                        .as("Windows retains at most one console host for the owned JVM").hasSizeLessThanOrEqualTo(1);
                children = live.stream().filter(child -> !isWindowsConsoleHost(child)).toList();
            }
            if (!children.isEmpty()) {
                Thread.sleep(50);
            }
        } while (!children.isEmpty() && System.nanoTime() < deadline);
        assertThat(children).as("Owned target descendants after bounded quiescence: %s",
                children.stream().map(child -> child.info().command().map(java.nio.file.Path::of)
                        .map(path -> path.getFileName().toString()).orElse("unknown")).toList()).isEmpty();
    }

    private boolean isWindowsConsoleHost(ProcessHandle process) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                && process.info().command().map(java.nio.file.Path::of)
                        .map(path -> path.getFileName().toString().equalsIgnoreCase("conhost.exe")).orElse(false);
    }

    private void assertLifecycleSafety(JvmProbeScenario s) throws Exception {
        Map<String, Object> self = Map.of("pid", Long.toString(s.server.pid()),
                "expectedProcessStartEpochMs", s.server.processStartEpochMs());
        for (String action : List.of("attach", "deactivate")) {
            assertThat(s.result("jvm_lifecycle." + action, self, true).path("reasonCode").asText())
                    .isEqualTo("mcp_server_attach_forbidden");
        }
        Map<String, Object> publicHost = new LinkedHashMap<>(s.attachArguments());
        publicHost.put("probeHost", "0.0.0.0");
        assertThat(s.result("jvm_lifecycle.attach", publicHost, true).path("reasonCode").asText())
                .isEqualTo("probe_host_not_allowed");
        assertThat(s.target.awaitSidecarReachability(s.probePort, false, Duration.ofSeconds(1))).isTrue();
    }

    private void assertBatchAndClassResetEffects(JvmProbeScenario s, String key, String other) throws Exception {
        JsonNode before = s.success("status", Map.of("keys", List.of(key, other, key))).path("entries");
        assertThat(before).hasSize(2);
        assertThat(before).allSatisfy(entry -> assertThat(entry.path("hitCount").asLong()).isPositive());
        s.success("reset", Map.of("keys", List.of(key, other, key)));
        JsonNode reset = s.success("status", Map.of("keys", List.of(key, other))).path("entries");
        assertThat(reset).hasSize(2);
        assertThat(reset).extracting(entry -> entry.path("key").asText()).containsExactly(key, other);
        assertThat(reset).allSatisfy(entry -> assertThat(entry.path("hitCount").asLong()).isZero());
        s.hit(key, "/api/v1/posts/101");
        String second = JvmProbeScenario.key(CONTROLLER, "listPosts", "return postService.listPosts(");
        s.hit(second, "/api/v1/posts?page=0&size=5");
        assertThat(s.status(key).path("hitCount").asLong()).isPositive();
        assertThat(s.status(second).path("hitCount").asLong()).isPositive();
        long outsideClass = s.status(other).path("hitCount").asLong();
        assertThat(outsideClass).isPositive();
        s.success("reset", Map.of("className", CONTROLLER));
        assertThat(s.status(key).path("hitCount").asLong()).isZero();
        assertThat(s.status(second).path("hitCount").asLong()).isZero();
        assertThat(s.status(other).path("hitCount").asLong()).isEqualTo(outsideClass);
        JavaItScenario.observe("probe.reset", "SUCCESS", "batch_all_members_and_class_scope_asserted");
    }

    private void assertRegistry(JvmProbeScenario s) throws Exception {
        JsonNode registry = JSON.valueToTree(Map.of("defaultProfile", "it", "profiles", Map.of("it",
                Map.of("probes", Map.of("owned", Map.of("baseUrl", s.baseUrl))))));
        JsonNode upsert = s.result("artifact_management.probe_config.upsert", Map.of("payload", registry), true);
        assertThat(upsert.path("details").path("reloadApplied").asBoolean()).isTrue();
        assertThat(JSON.readTree(Files.readString(s.workspace.resolve(".mcpjvm/probe-config.json"))))
                .isEqualTo(registry);
        assertThat(s.result("artifact_management.probe_config.read", Map.of(), false)
                .path("details").path("artifact")).isEqualTo(registry);
        assertThat(s.result("artifact_management.probe_config.validate", Map.of(), false)
                .path("details").path("probeCount").asInt()).isEqualTo(1);
        assertThat(s.result("artifact_management.probe_config.reload", Map.of(), true)
                .path("details").path("activeProbeCount").asInt()).isEqualTo(1);
        assertThat(s.result("probe.check", Map.of(), false).path("reasonCode").asText()).isEqualTo("SUCCESS");
        assertThat(s.result("probe.check", Map.of("probeId", "owned", "baseUrl", "invalid"), false)
                .path("reasonCode").asText()).isEqualTo("SUCCESS");
        assertThat(s.result("probe.check", Map.of("probeId", "unknown"), false)
                .path("reasonCode").asText()).isEqualTo("PROBE_ID_UNKNOWN");
    }

    private void assertCapture(JvmProbeScenario s, String key) throws Exception {
        JsonNode entry = s.status(key);
        assertThat(entry.path("lineValidation").asText()).isEqualTo("resolvable");
        String captureId = entry.path("capturePreview").path("captureId").asText();
        assertThat(captureId).isNotEmpty();
        JsonNode capture = s.success("capture", Map.of("captureId", captureId)).path("capture");
        assertThat(capture.path("captureId").asText()).isEqualTo(captureId);
        assertThat(capture.path("argsCount").asInt()).isPositive();
        assertThat(capture.path("hasReturnValue").asBoolean()).isTrue();
        assertThat(capture.path("executionDurationMs").asLong()).isEqualTo(
                capture.path("executionEndedAtEpoch").asLong() - capture.path("executionStartedAtEpoch").asLong());
        assertThat(capture.has("args")).isFalse();
        assertThat(capture.has("returnValue")).isFalse();
        assertThat(capture.path("executionPaths")).isEmpty();
        assertThat(s.probe("capture", Map.of("captureId", "absent")).path("reasonCode").asText())
                .isEqualTo("CAPTURE_NOT_FOUND");
    }
}
