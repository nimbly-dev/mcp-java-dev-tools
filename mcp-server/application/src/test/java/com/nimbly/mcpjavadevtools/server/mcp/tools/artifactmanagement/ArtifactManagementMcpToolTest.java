package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused Application Adapter mapping and deterministic output tests. */
class ArtifactManagementMcpToolTest {

    @Test
    void adapterMapsEnvelopeInvokesCoreAndFlattensDetails() {
        ArtifactManagementFeature feature = request -> ArtifactManagementResult.success(
                request.artifactType(), request.action(), Map.of("valid", true));
        ArtifactManagementMcpTool tool = new ArtifactManagementMcpTool(
                feature,
                new ArtifactManagementMcpRequestMapper(new ObjectMapper()),
                new ArtifactManagementMcpResponseMapper(),
                new com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor());

        var response = tool.execute(new ArtifactManagementMcpRequest(
                "probe_config",
                "validate",
                Map.of()));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.details()).containsEntry("artifactType", "probe_config");
        assertThat(response.details()).containsEntry("valid", true);
    }

    @Test
    void adapterReturnsInvalidRequestForUnknownDiscriminator() {
        ArtifactManagementFeature feature = request -> ArtifactManagementResult.success(
                request.artifactType(), request.action(), Map.of());
        ArtifactManagementMcpTool tool = new ArtifactManagementMcpTool(
                feature,
                new ArtifactManagementMcpRequestMapper(new ObjectMapper()),
                new ArtifactManagementMcpResponseMapper(),
                new com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor());

        var response = tool.execute(new ArtifactManagementMcpRequest(
                "unknown",
                "read",
                Map.of()));

        assertThat(response.reasonCode()).isEqualTo("artifact_management_request_invalid");
    }

    @Test
    void publicSchemaUsesTypedBoundedActionInputs() throws Exception {
        var schema = new ObjectMapper().readTree(
                ArtifactManagementMcpSchema.publicInputSchema(new ObjectMapper()));

        assertThat(schema.path("oneOf")).hasSize(30);
        for (var branch : schema.path("oneOf")) {
            assertThat(branch.path("properties").path("input").path("additionalProperties").asBoolean())
                    .isFalse();
        }
        var performanceUpsert = java.util.stream.StreamSupport.stream(
                        schema.path("oneOf").spliterator(), false)
                .filter(node -> "performance_plan".equals(
                        node.path("properties").path("artifactType").path("const").asText()))
                .filter(node -> "upsert".equals(
                        node.path("properties").path("action").path("const").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(performanceUpsert.path("properties").path("input").path("required").toString())
                .contains("planName", "payload");
    }

    @Test
    void runQuerySchemaPublishesCompatibilitySelectorsAndExportContextOptions() throws Exception {
        var schema = new ObjectMapper().readTree(
                ArtifactManagementMcpSchema.publicInputSchema(new ObjectMapper()));
        var query = java.util.stream.StreamSupport.stream(schema.path("oneOf").spliterator(), false)
                .filter(node -> "run_result".equals(node.path("properties").path("artifactType").path("const").asText()))
                .filter(node -> "query".equals(node.path("properties").path("action").path("const").asText()))
                .findFirst().orElseThrow().path("properties").path("input");
        assertThat(query.path("properties").has("strict")).isTrue();
        assertThat(query.path("properties").has("executionProfile")).isTrue();
        assertThat(query.path("properties").has("query")).isTrue();
        var queryProperties = query.path("properties").path("query").path("properties");
        for (String field : new String[] {"filters", "sort", "page", "detail", "watchers", "watcherEvidence", "cursor"}) {
            assertThat(queryProperties.has(field)).as(field).isTrue();
        }

        var export = java.util.stream.StreamSupport.stream(schema.path("oneOf").spliterator(), false)
                .filter(node -> "execution_export".equals(
                        node.path("properties").path("artifactType").path("const").asText()))
                .filter(node -> "generate".equals(
                        node.path("properties").path("action").path("const").asText()))
                .findFirst().orElseThrow().path("properties").path("input").path("properties");
        for (String field : new String[] {"includeRuntimeStartup", "includeHealthcheckGate",
                "contextBindings", "contextValues", "when"}) {
            assertThat(export.has(field)).as(field).isTrue();
        }
    }
}
