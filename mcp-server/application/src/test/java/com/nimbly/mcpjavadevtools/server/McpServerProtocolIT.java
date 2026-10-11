package com.nimbly.mcpjavadevtools.server;

import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.PROTOCOL_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Tag("lifecycle")
class McpServerProtocolIT extends McpServerStdioSupport {

    @Test
    void executableJarStartsInStdioModeWithoutContaminatingStdout() throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.send(initializeRequest());
            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);
            assertThat(initialize.path("result").path("capabilities").has("resources"))
                    .as("the required status resource must be advertised")
                    .isTrue();

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            JsonNode tools = server.responseFor(2).path("result").path("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools).extracting(tool -> tool.path("name").asText())
                    .containsExactlyInAnyOrder("operation_catalog", "operation_describe", "operation_execute");
            for (JsonNode tool : tools) {
                assertThat(tool.has("outputSchema")).isTrue();
            }

            server.send(request(90, "resources/list", Map.of()));
            assertThat(server.responseFor(90).path("result").path("resources"))
                    .extracting(resource -> resource.path("uri").asText())
                    .containsExactly("mcp-java-dev-tools://status");
            server.send(request(91, "resources/read", Map.of("uri", "mcp-java-dev-tools://unknown")));
            assertThat(server.responseFor(91).path("error").path("code").isNumber()).isTrue();
            assertThat(server.stderrText().length()).isLessThanOrEqualTo(16_385);

            JsonNode catalog = findTool(tools, "operation_catalog");
            JsonNode describe = findTool(tools, "operation_describe");
            JsonNode execute = findTool(tools, "operation_execute");
            assertThat(catalog.path("inputSchema").path("properties").has("query")).isTrue();
            assertThat(catalog.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(describe.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(execute.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(execute.path("inputSchema").path("properties").has("arguments")).isTrue();
            assertSchemaTypes(catalog.path("outputSchema"), "nextCursor", "string", "null");
            assertSchemaTypes(describe.path("outputSchema"), "operation", "object", "null");
            assertSchemaTypes(describe.path("outputSchema"), "defaultValue",
                    "null", "object", "array", "string", "number", "integer", "boolean");
            assertSchemaTypes(execute.path("outputSchema"), "operationId", "string", "null");
            assertSchemaTypes(execute.path("outputSchema"), "result",
                    "null", "object", "array", "string", "number", "integer", "boolean");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.exitCode()).isZero();
        }
    }

    @Test
    void executableJarReportsStartupFailureWithoutLeakingConfiguration() throws Exception {
        Path workspaceRoot = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(), workspaceRoot, "--spring.ai.mcp.server.stdio=not-a-boolean")) {
            server.awaitStartupFailure();

            assertThat(server.stdoutLines()).isEmpty();
            assertThat(server.stderrText())
                    .contains("mcp_java_dev_tools_startup_failed reasonCode=startup_failed")
                    .doesNotContain("not-a-boolean");
        }
    }

    @Test
    void executableJarMapsCatalogConfigurationIntoCorePolicy() throws Exception {
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(), workspaceRoot(),
                "--mcp.operation-catalog.default-limit=2",
                "--mcp.operation-catalog.maximum-limit=3")) {
            initialize(server);

            server.send(request(2, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of())));
            JsonNode defaultPage = toolPayload(server.responseFor(2));
            assertThat(defaultPage.path("status").asText()).isEqualTo("succeeded");
            assertThat(defaultPage.path("entries")).hasSize(2);

            server.send(request(3, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of("limit", 4))));
            JsonNode overConfiguredMaximum = toolPayload(server.responseFor(3));
            assertThat(overConfiguredMaximum.path("status").asText()).isEqualTo("invalid_input");
            assertThat(overConfiguredMaximum.path("reasonCode").asText())
                    .isEqualTo("catalog_limit_invalid");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.exitCode()).isZero();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigint() throws Exception {
        assertPosixSignalStopsExecutable("INT");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigterm() throws Exception {
        assertPosixSignalStopsExecutable("TERM");
    }

    private static void assertSchemaTypes(JsonNode schema, String property, String... expectedTypes) {
        JsonNode propertySchema = findPropertySchema(schema, property);
        assertThat(propertySchema)
                .as("schema for property %s in %s", property, schema)
                .isNotNull();
        assertThat(propertySchema.path("type"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(List.of(expectedTypes));
    }

    private static JsonNode findPropertySchema(JsonNode node, String property) {
        if (node == null || node.isValueNode()) {
            return null;
        }
        JsonNode properties = node.path("properties");
        if (properties.has(property)) {
            return properties.path(property);
        }
        for (JsonNode child : node) {
            JsonNode found = findPropertySchema(child, property);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

}
