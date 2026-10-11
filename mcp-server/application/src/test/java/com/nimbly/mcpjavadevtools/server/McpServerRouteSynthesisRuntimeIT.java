package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("route-synthesis")
@Timeout(120)
class McpServerRouteSynthesisRuntimeIT extends McpServerStdioSupport {
    @Test
    void mappingsSafetyFailureClassificationAndRecovery() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Map<String, Object> arguments = s.recipeArguments(JvmProbeScenario.CONTROLLER, "getPost");
            arguments.put("discoveryPreference", "runtime_only");
            String base = "http://127.0.0.1:" + s.runtime.target.port();
            for (Map.Entry<String, String> entry : Map.of(
                    "file:///private", "runtime_mappings_input_required",
                    "http://secret@127.0.0.1", "runtime_mappings_input_required",
                    base + "?secret=sentinel", "runtime_mappings_input_required",
                    "http://192.0.2.1", "runtime_mappings_destination_not_allowed",
                    base + "/actuator/health", "runtime_mapping_not_found",
                    base + "/route-selection/plain", "runtime_mappings_invalid_payload",
                    base + "/missing", "runtime_mappings_unreachable").entrySet()) {
                arguments.put("mappingsBaseUrl", entry.getKey());
                JsonNode failed = s.result("create_recipe", arguments);
                assertThat(failed.path("reasonCode").asText()).as(entry.getKey()).isEqualTo(entry.getValue());
                assertThat(failed.path("resultType").asText()).isEqualTo("report");
                assertThat(failed.toString()).doesNotContain("secret", "sentinel", "192.0.2.1");
            }
            arguments.putAll(Map.of("mappingsBaseUrl", base, "authToken", "bad-credential-sentinel"));
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText())
                    .isEqualTo("runtime_mappings_unauthorized");
            arguments.putAll(Map.of("authToken", "alice-token", "methodHint", "absent"));
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText()).isEqualTo("runtime_mapping_not_found");
            arguments.putAll(Map.of("methodHint", "select", "classHint",
                    "com.example.social.post.app.controller.RouteSelectionController"));
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText()).isEqualTo("runtime_mapping_ambiguous");
            arguments.putAll(Map.of("methodHint", "getPost", "classHint", JvmProbeScenario.CONTROLLER));
            assertThat(s.recipe(arguments).path("requestCandidates")).hasSize(1);
            assertResponseSizeBound(s, arguments);
        }
    }

    private void assertResponseSizeBound(RouteSynthesisScenario s, Map<String, Object> arguments) throws Exception {
        try (McpServerProcess bounded = JvmProbeScenario.startServer(s.runtime.workspace,
                "--mcpjvm.route-synthesis.runtime-mappings-max-response-bytes=64")) {
            initialize(bounded);
            bounded.send(executeRequest(200, "route_synthesis.create_recipe", arguments, true));
            JsonNode result = toolPayload(bounded.responseFor(200)).path("result");
            assertThat(result.path("reasonCode").asText()).isEqualTo("runtime_mappings_response_too_large");
            assertThat(result.path("evidence").toString()).contains("responseLimitBytes=64");
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "real_mappings_response_byte_bound");
        }
    }

    @Test
    void slowMappingsBodyIsBoundedAndServerRecovers() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start(); DelayedHttpTarget target = DelayedHttpTarget.start()) {
            Map<String, Object> arguments = s.recipeArguments(JvmProbeScenario.CONTROLLER, "getPost");
            arguments.putAll(Map.of("discoveryPreference", "runtime_only", "mappingsBaseUrl", target.url()));
            long started = System.nanoTime();
            JsonNode failed = s.result("create_recipe", arguments);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(failed.path("reasonCode").asText()).isEqualTo("runtime_mappings_unreachable");
            target.awaitClientDisconnect();
            arguments.put("mappingsBaseUrl", "http://127.0.0.1:" + s.runtime.target.port());
            assertThat(s.recipe(arguments).path("requestCandidates")).hasSize(1);
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "slow_body_bounded_connection_closed_recovered");
        }
    }

    @Test
    void cancellationClosesMappingReadAndAllowsRecovery() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start(); DelayedHttpTarget target = DelayedHttpTarget.start()) {
            Map<String, Object> arguments = s.recipeArguments(JvmProbeScenario.CONTROLLER, "getPost");
            arguments.putAll(Map.of("discoveryPreference", "runtime_only", "mappingsBaseUrl", target.url()));
            s.runtime.server.send(executeRequest(700, "route_synthesis.create_recipe", arguments, true));
            target.awaitRequest();
            target.assertStillConnected();
            s.runtime.server.send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                    "params", Map.of("requestId", 700, "reason", "route integration cancellation")));
            target.awaitClientDisconnect();
            arguments.put("mappingsBaseUrl", "http://127.0.0.1:" + s.runtime.target.port());
            assertThat(s.recipe(arguments).path("requestCandidates")).hasSize(1);
            s.runtime.server.closeInputAndAwaitTermination();
            assertThat(s.runtime.server.hasResponseFor(700)).isFalse();
            assertThat(s.runtime.target.isHealthy()).isTrue();
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "cancelled_read_closed_no_late_response_recovered");
        }
    }
}
