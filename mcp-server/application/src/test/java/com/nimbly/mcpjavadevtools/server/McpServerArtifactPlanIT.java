package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("artifact")
@Timeout(120)
class McpServerArtifactPlanIT extends ArtifactMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"regression", "performance", "security"})
    void planLifecycleRoundTripsRealFilesAndSurvivesRestart(String suite) throws Exception {
        Path workspace = JavaItScenario.workspace();
        ObjectNode payload = plan(suite, "http://127.0.0.1:1");
        Map<String, Object> selected = Map.of("projectName", PROJECT, "planName", "plan");
        String family = suite + "_plan.";
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            assertThat(ok(server, family + "list", Map.of()).path("planNames")).isEmpty();
            blocked(server, family + "read", selected, "artifact_missing");
            blocked(server, family + "validate", selected, "artifact_missing");
            assertThat(execute(server, "artifact_management." + family + "upsert", planInput(suite, payload), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            ok(server, family + "upsert", planInput(suite, payload));
            Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/" + suite + "/plan");
            assertThat(JSON.readTree(directory.resolve("metadata.json").toFile())).isEqualTo(payload.path("metadata"));
            assertThat(JSON.readTree(directory.resolve("contract.json").toFile())).isEqualTo(payload.path("contract"));
            assertThat(Files.readString(directory.resolve("plan.md"))).isEqualTo(payload.path("plan").asText());
            ok(server, family + "validate", selected);
            assertThat(ok(server, family + "list", Map.of()).path("planNames")).extracting(JsonNode::asText).containsExactly("plan");
            assertPlanRead(server, suite, payload);
            ObjectNode invalid = payload.deepCopy();
            invalid.remove("contract");
            blocked(server, family + "upsert", planInput(suite, invalid), "plan_contract_invalid");
            assertThat(JSON.readTree(directory.resolve("contract.json").toFile())).isEqualTo(payload.path("contract"));
            blocked(server, family + "upsert", Map.of("projectName", PROJECT, "planName", "../outside", "payload", payload),
                    "artifact_path_segment_invalid");
            McpServerArtifactConfigIT.assertNoTemporaryFiles(workspace);
        }
        try (McpServerProcess restarted = start(workspace)) {
            assertPlanRead(restarted, suite, payload);
        }
    }

    private void assertPlanRead(McpServerProcess server, String suite, ObjectNode payload) throws Exception {
        JsonNode read = ok(server, suite + "_plan.read", Map.of("projectName", PROJECT, "planName", "plan",
                "query", Map.of("select", List.of("metadata", "contract", "plan"))));
        assertThat(read.path("artifact").path("metadata")).isEqualTo(payload.path("metadata"));
        assertThat(read.path("artifact").path("contract")).isEqualTo(payload.path("contract"));
        assertThat(read.path("artifact").path("plan").asText()).isEqualTo(payload.path("plan").asText());
    }

    @Test
    void suiteSpecificValidationAndRegressionProjectionWindows() throws Exception {
        try (McpServerProcess server = start(JavaItScenario.workspace())) {
            for (String suite : List.of("regression", "performance", "security")) {
                ObjectNode invalid = plan(suite, "http://127.0.0.1:1");
                if (suite.equals("performance")) {
                    invalid.with("contract").with("loadModel").put("concurrency", 0);
                } else if (suite.equals("regression")) {
                    ((ObjectNode) invalid.path("contract").path("steps").get(0)).put("protocol", "kafka");
                } else {
                    invalid.with("contract").put("suiteType", "regression");
                }
                String reason = Map.of("regression", "transport_protocol_mismatch", "performance", "performance_load_model_invalid",
                        "security", "security_plan_contract_invalid").get(suite);
                blocked(server, suite + "_plan.upsert", planInput(suite, invalid), reason);
            }
            ObjectNode payload = plan("regression", "http://127.0.0.1:1");
            payload.with("contract").putArray("prerequisites").addObject().put("id", "prepare");
            ok(server, "regression_plan.upsert", planInput("regression", payload));
            JsonNode summary = ok(server, "regression_plan.read", Map.of("planName", "plan", "projectName", PROJECT));
            assertThat(summary.has("artifact")).isFalse();
            assertThat(summary.path("summary").path("stepCount").asInt()).isEqualTo(1);
            blocked(server, "regression_plan.read", Map.of("projectName", PROJECT, "planName", "plan",
                    "query", Map.of("select", List.of("steps"))), "window_query_required");
            JsonNode window = ok(server, "regression_plan.read", Map.of("projectName", PROJECT, "planName", "plan", "query", Map.of(
                    "select", List.of("steps", "prerequisites", "targets"),
                    "steps", Map.of("offset", 0, "limit", 1), "prerequisites", Map.of("offset", 1, "limit", 1))));
            assertThat(window.path("steps").path("items").get(0).path("id").asText()).isEqualTo("health");
            assertThat(window.path("prerequisites").path("items")).isEmpty();
            assertThat(window.path("prerequisites").path("total").asInt()).isEqualTo(1);
            assertThat(window.path("targets")).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ps1", "sh", "postman"})
    void exportGenerateReadListSecretsAndRestart(String mode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String exportId;
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ok(server, "regression_plan.upsert", planInput("regression", plan("regression", "http://127.0.0.1:1/health")));
            assertThat(ok(server, "execution_export.list", Map.of()).path("exportFolders")).isEmpty();
            Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", "regression", "mode", mode,
                    "includeResolvedSecrets", false, "contextValues", Map.of("auth.bearer", SECRET),
                    "includeRuntimeStartup", false, "includeHealthcheckGate", false);
            assertThat(execute(server, "artifact_management.execution_export.generate", input, false).path("status").asText())
                    .isEqualTo("confirmation_required");
            JsonNode generated = ok(server, "execution_export.generate", input);
            exportId = generated.path("exportId").asText();
            assertThat(exportId).isNotBlank();
            Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/exports/" + exportId);
            assertThat(directory.resolve("manifest.json")).isRegularFile();
            assertThat(JSON.readTree(directory.resolve("manifest.json").toFile()).isObject()).isTrue();
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).as("Export files exclude resolved secrets").doesNotContain(SECRET);
                }
            }
            assertThat(ok(server, "execution_export.generate", input).path("exportId").asText()).isEqualTo(exportId);
            assertExportReadable(server, exportId);
        }
        try (McpServerProcess restarted = start(workspace)) {
            assertExportReadable(restarted, exportId);
        }
    }

    private void assertExportReadable(McpServerProcess server, String exportId) throws Exception {
        assertThat(ok(server, "execution_export.list", Map.of("projectName", PROJECT)).path("exportFolders"))
                .extracting(JsonNode::asText).containsExactly(exportId);
        assertThat(ok(server, "execution_export.read", Map.of("projectName", PROJECT, "query", Map.of("exportId", exportId))).path("files"))
                .extracting(JsonNode::asText).contains("manifest.json");
    }

    @Test
    void resolvedExportSecretsRequireExplicitConfirmedOptInAndStayOutOfMcp() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ok(server, "regression_plan.upsert", planInput("regression", plan("regression", "http://127.0.0.1:1/health")));
            Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", "regression", "mode", "sh",
                    "includeResolvedSecrets", true, "contextValues", Map.of("auth.bearer", SECRET));
            assertThat(execute(server, "artifact_management.execution_export.generate", input, false).path("status").asText())
                    .isEqualTo("confirmation_required");
            JsonNode generated = ok(server, "execution_export.generate", input);
            Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/exports/" + generated.path("exportId").asText());
            assertThat(Files.readString(directory.resolve("project.env"))).contains("SENSITIVE EXPORT", "AUTH_TOKEN=" + SECRET);
            assertThat(server.stderrText()).doesNotContain(SECRET);
            assertThat(server.stdoutLines()).noneSatisfy(line -> assertThat(line).contains(SECRET));
        }
    }

    @Test
    void performanceExportAndInvalidExportInputsStayWithinOwnedPaths() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ok(server, "performance_plan.upsert", planInput("performance", plan("performance", "http://127.0.0.1:1")));
            for (String mode : List.of("ps1", "sh")) {
                JsonNode generated = ok(server, "execution_export.generate", Map.of("projectName", PROJECT, "executionProfile", "performance", "mode", mode));
                Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/exports/" + generated.path("exportId").asText());
                assertThat(directory.resolve("performance-export.bundle.json")).isRegularFile();
                assertThat(Files.readString(directory.resolve("artifacts/jmeter/plan.workload.jmeter.jmx"))).contains("<jmeterTestPlan");
            }
            blocked(server, "execution_export.generate", Map.of("projectName", PROJECT, "executionProfile", "performance", "mode", "postman"),
                    "performance_export_mode_unsupported");
            ObjectNode security = plan("security", "http://127.0.0.1:1");
            ObjectNode entrypoint = (ObjectNode) security.path("contract").path("entrypoints").get(0);
            entrypoint.with("transport").put("baseUrl", "http://127.0.0.1:1");
            entrypoint.putObject("request").put("path", "/actuator/health");
            ok(server, "security_plan.upsert", planInput("security", security));
            blocked(server, "execution_export.generate", Map.of("projectName", PROJECT, "executionProfile", "security", "mode", "sh"),
                    "security_export_unsupported");
            blocked(server, "execution_export.read", Map.of("projectName", PROJECT), "export_id_required");
            blocked(server, "execution_export.read", Map.of("projectName", PROJECT, "query", Map.of("exportId", "../escape")),
                    "artifact_path_segment_invalid");
            assertThat(execute(server, "artifact_management.execution_export.generate", Map.of("mode", "zip"), true)
                    .path("status").asText()).isEqualTo("invalid_input");
            blocked(server, "execution_export.generate", Map.of("projectName", PROJECT, "executionProfile", "absent", "mode", "sh"),
                    "execution_profile_not_found");
            McpServerArtifactConfigIT.assertNoTemporaryFiles(workspace);
        }
    }
}
