package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("orchestration")
@Timeout(120)
class McpServerOrchestrationValidationIT extends OrchestrationMcpSupport {
    @Test
    void cdeDiscoveryDescribesTheSameSafetyContractAndConfirmationPreventsExports() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            int id = 940;
            for (String api : List.of("execution_orchestration", "execution_profile_export")) {
                String operation = api.equals("execution_orchestration") ? OPERATION : api + ".export";
                server.send(request(id, "tools/call", Map.of("name", "operation_catalog", "arguments", Map.of("api", api))));
                assertThat(toolPayload(server.responseFor(id++)).toString()).contains(operation);
                server.send(request(id, "tools/call", Map.of("name", "operation_describe", "arguments", Map.of("operationId", operation))));
                JsonNode description = toolPayload(server.responseFor(id++));
                assertThat(description.path("operation").path("safety").path("confirmationRequired").asBoolean()).isTrue();
                assertThat(description.path("operation").path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
            }
            JsonNode response = execute(server, "execution_profile_export.export", Map.of("projectName", PROJECT, "mode", "sh"), false);
            assertThat(response.path("status").asText()).isEqualTo("confirmation_required");
            assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/exports")).doesNotExist();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "profile", "slice-zero", "slice-negative", "slice-fraction", "slice-string", "extra", "run-path"})
    void invalidInputsRejectBeforeEffects(String variant) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "plan"));
            save(server, "plan", generatedPlan(target.url("/must-not-run")));
            Map<String, Object> input = new java.util.LinkedHashMap<>(Map.of("projectName", PROJECT, "executionProfile", PROFILE));
            switch (variant) {
                case "project" -> input.remove("projectName");
                case "profile" -> input.remove("executionProfile");
                case "slice-zero" -> input.put("maxPlansPerCall", 0);
                case "slice-negative" -> input.put("maxPlansPerCall", -1);
                case "slice-fraction" -> input.put("maxPlansPerCall", 1.5);
                case "slice-string" -> input.put("maxPlansPerCall", "1");
                case "extra" -> input.put("unexpected", true);
                default -> input.put("suiteRunId", "../outside");
            }
            JsonNode response = execute(server, OPERATION, input, true);
            if (variant.equals("run-path")) {
                assertStatus(response.path("result"), "blocked", "suite_run_id_invalid");
            } else {
                assertThat(response.path("status").asText()).isEqualTo("invalid_input");
                assertThat(response.path("reasonCode").asText()).isEqualTo("operation_input_schema_invalid");
            }
            target.assertNoRequest();
            assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/suite-runs")).doesNotExist();
        }
    }

    @Test
    void invalidProfileOrderingFailsArtifactValidationWithoutExecution() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "first", "second");
            ((ObjectNode) profile(project).path("plans").get(0)).put("order", 2);
            ((ObjectNode) profile(project).path("plans").get(1)).put("order", 1);
            blocked(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project), "execution_profile_invalid");
            target.assertNoRequest();
        }
    }

    @Test
    void unsupportedExportsAndMissingSelectorsRemainDeterministic() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            for (String suite : List.of("regression", "performance", "security")) {
                ObjectNode plan = suite.equals("security") ? SecurityMcpSupport.generatedPlan(target.url("")) : plan(suite, target.url(""));
                ok(server, suite + "_plan.upsert", planInput(suite, plan));
            }
            JsonNode unsupported = execute(server, "execution_profile_export.export", Map.of("projectName", PROJECT, "mode", "jmx"), true);
            assertThat(unsupported.path("status").asText()).isEqualTo("invalid_input");
            assertThat(unsupported.path("reasonCode").asText()).isEqualTo("operation_input_schema_invalid");
            exportRejected(server, Map.of(), "execution_export_mode_required");
            exportRejected(server, Map.of("mode", "ps1", "type", "sh"), "execution_export_mode_conflict");
            exportRejected(server, Map.of("mode", "postman", "executionProfile", "performance"), "performance_export_mode_unsupported");
            exportRejected(server, Map.of("mode", "ps1", "executionProfile", "security"), "security_export_unsupported");
            exportRejected(server, Map.of("mode", "sh", "executionProfile", "unknown"), "execution_profile_not_found");
            exportRejected(server, Map.of("mode", "sh"), "execution_export_workload_required");
            exportRejected(server, Map.of("mode", "sh", "contextBindings", Map.of("base", "INVALID-KEY")),
                    "execution_export_context_binding_invalid");
            target.assertNoRequest();
        }
    }

    private void exportRejected(McpServerProcess server, Map<String, Object> input, String reason) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(input);
        arguments.put("projectName", PROJECT);
        JsonNode response = execute(server, "execution_profile_export.export", arguments, true);
        assertThat(response.path("status").asText()).isEqualTo("succeeded");
        assertThat(response.path("result").path("reasonCode").asText()).as(response.toPrettyString()).isEqualTo(reason);
        assertThat(response.path("result").path("status").asText()).isEqualTo(reason);
    }
}
