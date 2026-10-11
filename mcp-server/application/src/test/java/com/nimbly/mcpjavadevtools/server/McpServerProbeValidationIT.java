package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Shared validation equivalence groups execute the public CDE boundary, with no attach side effects. */
@Tag("jvm-probe")
@Timeout(90)
class McpServerProbeValidationIT extends McpServerStdioSupport {

    @Test
    void allOperationSchemasRejectInvalidInputsAndRecover() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
            initialize(server);
            int id = 200;
            for (String operation : List.of("jvm_lifecycle.list_jvms", "jvm_lifecycle.attach", "jvm_lifecycle.deactivate",
                    "probe.check", "probe.status", "probe.reset", "probe.wait_for_hit", "probe.capture", "probe.actuate",
                    "probe.profiler")) {
                server.send(executeRequest(id, operation, Map.of("unknown647", "647-secret-marker"), true));
                JsonNode result = toolPayload(server.responseFor(id++));
                assertThat(result.path("status").asText()).isEqualTo("invalid_input");
                assertThat(result.toString()).doesNotContain("647-secret-marker", workspace.toString());
                JavaItScenario.observe(operation, "invalid_input", "unknown_argument");
            }
            for (String operation : List.of("probe.check", "probe.status", "probe.reset", "probe.wait_for_hit",
                    "probe.capture", "probe.actuate", "probe.profiler")) {
                server.send(executeRequest(id, operation, Map.of("timeoutMs", 0), true));
                assertThat(toolPayload(server.responseFor(id++)).path("status").asText()).isEqualTo("invalid_input");
            }
            assertLifecycleValidation(server, id);
        }
    }

    private void assertLifecycleValidation(McpServerProcess server, int id) throws Exception {
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
                Map.of(), Map.of("pid", "not-pid", "expectedProcessStartEpochMs", 1),
                Map.of("pid", "1", "expectedProcessStartEpochMs", 0),
                Map.of("pid", "1", "expectedProcessStartEpochMs", 1, "probePort", 0),
                Map.of("pid", "1", "expectedProcessStartEpochMs", 1, "probePort", 65536))) {
            server.send(executeRequest(id, "jvm_lifecycle.attach", arguments, true));
            assertThat(toolPayload(server.responseFor(id++)).path("status").asText()).isEqualTo("invalid_input");
        }
        server.send(executeRequest(id, "jvm_lifecycle.attach", Map.of("pid", "1",
                "expectedProcessStartEpochMs", 1, "include", " "), true));
        assertThat(toolPayload(server.responseFor(id++)).path("reasonCode").asText()).isEqualTo("operation_execution_failed");
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
                Map.of("keys", List.of()), Map.of("key", "x", "keys", List.of("x")),
                Map.of("className", "x", "key", "x"))) {
            server.send(executeRequest(id, "probe.reset", arguments, true));
            JsonNode invalid = toolPayload(server.responseFor(id++));
            if (arguments.containsKey("keys") && ((List<?>) arguments.get("keys")).isEmpty()) {
                assertThat(invalid.path("status").asText()).isEqualTo("invalid_input");
            } else {
                assertThat(invalid.path("reasonCode").asText()).isEqualTo("operation_execution_failed");
            }
        }
        server.send(executeRequest(id, "probe.profiler", Map.of("action", "start", "provider", "unsupported"), true));
        assertThat(toolPayload(server.responseFor(id++)).path("status").asText()).isEqualTo("invalid_input");
        server.send(executeRequest(id, "probe.actuate", Map.of("action", "unknown", "sessionId", "647"), true));
        assertThat(toolPayload(server.responseFor(id++)).path("status").asText()).isEqualTo("invalid_input");
        server.send(request(id, "tools/list", Map.of()));
        assertThat(server.responseFor(id).path("result").path("tools")).hasSize(3);
    }

    @Test
    void probeRoutingMissingRegistryAmbiguityInvalidTargetAndLineHints() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            assertThat(s.result("probe.check", Map.of(), false).path("reasonCode").asText()).isEqualTo("PROBE_ID_REQUIRED");
            assertThat(s.probe("check", Map.of("baseUrl", "file:///not-http"))
                    .path("reasonCode").asText()).isEqualTo("INVALID_PROBE_TARGET");
            s.attach();
            Map<String, Object> probes = Map.of("one", Map.of("baseUrl", s.baseUrl),
                    "two", Map.of("baseUrl", "http://127.0.0.1:" + freePort()));
            JsonNode registry = JSON.valueToTree(Map.of("defaultProfile", "it", "profiles",
                    Map.of("it", Map.of("probes", probes))));
            s.result("artifact_management.probe_config.upsert", Map.of("payload", registry), true);
            assertThat(s.result("probe.check", Map.of(), false).path("reasonCode").asText()).isEqualTo("PROBE_ID_REQUIRED");
            assertThat(s.result("probe.check", Map.of("probeId", "one"), false)
                    .path("reasonCode").asText()).isEqualTo("SUCCESS");
            String key = s.postKey();
            String method = key.substring(0, key.lastIndexOf(':'));
            int line = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
            assertThat(s.success("status", Map.of("key", method, "lineHint", line))
                    .path("entries").get(0).path("key").asText()).isEqualTo(key);
            assertThat(s.probe("status", Map.of("key", method)).path("reasonCode").asText()).isEqualTo("LINE_KEY_REQUIRED");
            assertThat(s.probe("wait_for_hit", Map.of("key", "invalid", "timeoutMs", 100))
                    .path("reasonCode").asText()).isEqualTo("LINE_KEY_REQUIRED");
            s.success("reset", Map.of("key", method, "lineHint", line));
            s.hit(key, "/api/v1/posts/101");
            Thread.sleep(50);
            assertThat(s.probe("wait_for_hit", Map.of("key", key, "timeoutMs", 100,
                    "pollIntervalMs", 20, "maxRetries", 1)).path("reasonCode").asText()).isEqualTo("WAIT_TIMEOUT");
            s.deactivate();
        }
    }
}
