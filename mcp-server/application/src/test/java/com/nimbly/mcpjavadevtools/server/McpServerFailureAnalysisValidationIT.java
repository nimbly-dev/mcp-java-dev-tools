package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("failure-analysis")
@Timeout(120)
class McpServerFailureAnalysisValidationIT extends McpServerStdioSupport {
    private int nextId = 100;

    @Test
    void catalogDescribeAndEveryTerminalOutcomeCleanupAndMode() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
            initialize(server);
            server.send(request(20, "tools/call", Map.of("name", "operation_catalog",
                    "arguments", Map.of("api", "failure_analysis"))));
            assertThat(toolPayload(server.responseFor(20)).path("entries"))
                    .extracting(entry -> entry.path("operationId").asText()).containsExactlyInAnyOrder(
                            "failure_analysis.analyze_trace", "failure_analysis.verify_reproduction");
            for (String action : List.of("analyze_trace", "verify_reproduction")) {
                server.send(request(21, "tools/call", Map.of("name", "operation_describe",
                        "arguments", Map.of("operationId", "failure_analysis." + action))));
                JsonNode description = toolPayload(server.responseFor(21)).path("operation");
                assertThat(description.path("inputSchema").path("properties").path("investigation").isObject()).isTrue();
                assertThat(description.path("safety").path("confirmationRequired").asBoolean()).isFalse();
            }
            for (String mode : List.of("guided", "hands_off")) {
                for (String cleanup : List.of("cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned")) {
                    assertTerminals(server, mode, cleanup);
                }
            }
        }
    }

    private void assertTerminals(McpServerProcess server, String mode, String cleanup) throws Exception {
        for (String outcome : List.of("BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH", "BLOCKED_MISSING_TRIGGER",
                "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION", "ENVIRONMENT_MISMATCH", "INCONCLUSIVE", "CANCELLED")) {
            int attempts = cleanup.equals("cleanup_confirmed") ? 0 : 10;
            Map<String, Object> input = new LinkedHashMap<>(terminal(outcome, cleanup, attempts));
            input.put("investigation", FailureAnalysisScenario.investigation(mode));
            JsonNode payload = call(server, "verify_reproduction", input);
            assertThat(payload.path("status").asText()).isEqualTo("succeeded");
            JsonNode result = payload.path("result");
            McpServerFailureAnalysisIT.assertNoDiagnosis(result, outcome, "bounded_investigation_ended");
            assertThat(result.path("cleanupStatus").asText()).isEqualTo(cleanup);
            assertThat(result.path("attemptEvidence").path("attemptCount").asInt()).isEqualTo(attempts);
            assertThat(result.path("investigation")).isEqualTo(JSON.valueToTree(input.get("investigation")));
            assertThat(result.path("observedFingerprint").isNull()).isTrue();
        }
    }

    static Map<String, Object> terminal(String outcome, String cleanup, int attempts) {
        return Map.of("terminalState", Map.of("outcome", outcome, "reasonCode", "bounded_investigation_ended",
                "cleanupStatus", cleanup, "attemptCount", attempts));
    }

    @Test
    void invalidSchemasBoundsAndMutuallyExclusiveVariantsNeverClaimDiagnosis() throws Exception {
        try (McpServerProcess server = JvmProbeScenario.startServer(JavaItScenario.workspace())) {
            initialize(server);
            Map<String, Object> analyze = Map.of("trace", "invalid trace", "sidecarBaseUrl", "http://127.0.0.1:1");
            assertInvalidFields(server, "analyze_trace", analyze, Map.of("trace", List.of("", "x".repeat(200_001)),
                    "timeoutMs", List.of(999, 30_001), "sidecarAuthorization", List.of("", "x".repeat(8_193))));
            assertMissingFields(server, "analyze_trace", analyze);
            Map<String, Object> runtime = new LinkedHashMap<>(Map.of("captureId", "validation-only",
                    "expectedFingerprint", FailureAnalysisScenario.expected(), "sidecarBaseUrl", "http://127.0.0.1:1",
                    "lineHit", Map.of("strictLineKey", "example.Service#method:1", "hitCount", 1)));
            assertMissingFields(server, "verify_reproduction", runtime);
            assertInvalidFields(server, "verify_reproduction", runtime,
                    Map.of("timeoutMs", List.of(999, 30_001), "captureId", List.of("", " "),
                            "lineHit", List.of(Map.of("strictLineKey", "bad", "hitCount", 0)),
                            "expectedFingerprint", List.of(Map.of("exceptionType", " "))));
            runtime.putAll(terminal("CANCELLED", "cleanup_confirmed", 0));
            assertInvalid(call(server, "verify_reproduction", runtime));
            for (String action : List.of("analyze_trace", "verify_reproduction")) {
                Map<String, Object> input = new LinkedHashMap<>(action.equals("analyze_trace") ? analyze
                        : terminal("INCONCLUSIVE", "cleanup_confirmed", 0));
                assertInvalidInvestigations(server, action, input);
                input.put("unknownField", "validation-secret-sentinel");
                assertInvalid(call(server, action, input));
            }
            assertInvalidTerminals(server);
        }
    }

    private void assertInvalidTerminals(McpServerProcess server) throws Exception {
        Map<String, Object> valid = new LinkedHashMap<>(Map.of("outcome", "INCONCLUSIVE", "reasonCode", "bounded",
                "cleanupStatus", "cleanup_confirmed", "attemptCount", 0));
        for (var entry : Map.of("outcome", List.of("REPRODUCED", "unknown"), "reasonCode", List.of("", "x".repeat(121)),
                "cleanupStatus", List.of("unknown"), "attemptCount", List.of(-1, 11)).entrySet()) {
            for (Object value : entry.getValue()) {
                Map<String, Object> invalid = new LinkedHashMap<>(valid);
                invalid.put(entry.getKey(), value);
                assertInvalid(call(server, "verify_reproduction", Map.of("terminalState", invalid)));
            }
        }
        assertMissingFields(server, "verify_reproduction", Map.of("terminalState", valid));
        for (String field : valid.keySet()) {
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            invalid.remove(field);
            assertInvalid(call(server, "verify_reproduction", Map.of("terminalState", invalid)));
        }
    }

    private void assertInvalidInvestigations(McpServerProcess server, String action, Map<String, Object> input) throws Exception {
        for (Map<String, Object> investigation : List.of(Map.<String, Object>of("mode", "guided"),
                Map.<String, Object>of("mode", "unknown", "attemptLimit", 1, "elapsedTimeLimitMs", 1_000),
                Map.<String, Object>of("mode", "guided", "attemptLimit", 0, "elapsedTimeLimitMs", 1_000),
                Map.<String, Object>of("mode", "guided", "attemptLimit", 11, "elapsedTimeLimitMs", 1_000),
                Map.<String, Object>of("mode", "guided", "attemptLimit", 1, "elapsedTimeLimitMs", 999),
                Map.<String, Object>of("mode", "guided", "attemptLimit", 1, "elapsedTimeLimitMs", 300_001))) {
            input.put("investigation", investigation);
            assertInvalid(call(server, action, input));
        }
        input.remove("investigation");
    }

    private void assertMissingFields(McpServerProcess server, String action, Map<String, Object> valid) throws Exception {
        for (String field : valid.keySet()) {
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            invalid.remove(field);
            assertInvalid(call(server, action, invalid));
        }
    }

    private void assertInvalidFields(McpServerProcess server, String action, Map<String, Object> valid,
            Map<String, ? extends List<?>> fields) throws Exception {
        for (var entry : fields.entrySet()) {
            for (Object value : entry.getValue()) {
                Map<String, Object> invalid = new LinkedHashMap<>(valid);
                invalid.put(entry.getKey(), value);
                assertInvalid(call(server, action, invalid));
            }
        }
    }

    private void assertInvalid(JsonNode payload) {
        assertThat(payload.path("status").asText()).as(payload.toPrettyString()).isEqualTo("invalid_input");
        assertThat(payload.toString()).doesNotContain("validation-secret-sentinel", "diagnosisClaimed\":true");
    }

    @Test
    void terminalReasonAndMissingCaptureDiagnosticsRedactCredentials() throws Exception {
        String secret = "649-terminal-secret-sentinel";
        try (McpServerProcess server = JvmProbeScenario.startServer(JavaItScenario.workspace())) {
            initialize(server);
            Map<String, Object> state = Map.of("outcome", "INCONCLUSIVE", "reasonCode", "password=" + secret,
                    "cleanupStatus", "cleanup_confirmed", "attemptCount", 0);
            JsonNode payload = call(server, "verify_reproduction", Map.of("terminalState", state));
            assertThat(payload.path("status").asText()).isEqualTo("succeeded");
            assertThat(payload.toString()).doesNotContain(secret);
            assertThat(payload.path("result").path("reasonCode").asText()).isEqualTo("<redacted>");
            assertThat(server.stderrText()).doesNotContain(secret);
            for (String reason : List.of("operator_cancelled_after_confirming_missing_trigger",
                    "step2-cancelled-after-confirming-missing-trigger",
                    "task-cancelled-after-confirming-missing-trigger", "cancelled_".repeat(11) + "cleanup_ok")) {
                Map<String, Object> safeState = Map.of("outcome", "CANCELLED", "reasonCode", reason,
                        "cleanupStatus", "cleanup_confirmed", "attemptCount", 0);
                JsonNode safe = call(server, "verify_reproduction", Map.of("terminalState", safeState));
                assertThat(safe.path("status").asText()).isEqualTo("succeeded");
                McpServerFailureAnalysisIT.assertNoDiagnosis(safe.path("result"), "CANCELLED", reason);
            }
            for (String reason : List.of("Bearer " + secret, "a".repeat(48),
                    "operator_cancelled_after_confirming_missing_trigger password=" + secret,
                    "ghp_" + "a".repeat(40), "sk-proj-" + "a".repeat(48),
                    "operator_cancelled_ghp_" + "a".repeat(40),
                    "sk-proj-" + ("a".repeat(16) + "-").repeat(2) + "a".repeat(16))) {
                Map<String, Object> sensitiveState = Map.of("outcome", "CANCELLED", "reasonCode", reason,
                        "cleanupStatus", "cleanup_confirmed", "attemptCount", 0);
                JsonNode sensitive = call(server, "verify_reproduction", Map.of("terminalState", sensitiveState));
                assertThat(sensitive.path("status").asText()).isEqualTo("succeeded");
                McpServerFailureAnalysisIT.assertNoDiagnosis(sensitive.path("result"), "CANCELLED", "<redacted>");
                assertThat(sensitive.toString()).doesNotContain(secret, reason);
                assertThat(server.stderrText()).doesNotContain(reason);
            }
        }
    }

    private JsonNode call(McpServerProcess server, String action, Map<String, Object> input) throws Exception {
        int id = nextId++;
        server.send(executeRequest(id, "failure_analysis." + action, input, false));
        JsonNode payload = toolPayload(server.responseFor(id));
        JavaItScenario.observe("failure_analysis." + action, payload.path("status").asText(),
                payload.path("result").path("reasonCode").asText());
        return payload;
    }
}
