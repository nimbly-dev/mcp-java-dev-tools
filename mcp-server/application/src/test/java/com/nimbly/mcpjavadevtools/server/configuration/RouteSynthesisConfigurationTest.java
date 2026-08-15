package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis.RouteSynthesisMcpSchemaPostProcessor;
import com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis.RouteSynthesisMcpTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class RouteSynthesisConfigurationTest {

    @Test
    void registersTheFullCorrelatedRouteSynthesisSchema() throws Exception {
        assertThat(RouteSynthesisMcpTool.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(RouteSynthesisMcpTool.class
                .getDeclaredMethod("execute", String.class,
                        com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis.RouteSynthesisMcpActionInput.class)
                .isAnnotationPresent(McpTool.class)).isTrue();
        RouteSynthesisFeature feature = request -> RouteSynthesisResult.report(
                "report", "invalid_request", "request_validation", "fix_input", "Fix input.");
        List<McpServerFeatures.SyncToolSpecification> specifications = (List<McpServerFeatures.SyncToolSpecification>)
                new RouteSynthesisMcpSchemaPostProcessor().postProcessAfterInitialization(
                        org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders
                                .toolSpecifications(List.of(new RouteSynthesisMcpTool(feature))),
                        "toolSpecs");

        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0).tool().name()).isEqualTo("route_synthesis");
        JsonNode schema = new ObjectMapper().valueToTree(specifications.get(0).tool().inputSchema());
        assertThat(schema.path("required").toString()).isEqualTo("[\"action\",\"input\"]");
        assertThat(schema.path("oneOf")).hasSize(4);
        assertThat(schema.at("/oneOf/3/properties/action/const").asText()).isEqualTo("create_recipe");
        assertThat(schema.at("/oneOf/3/properties/input/required").toString())
                .contains("classHint", "methodHint", "intentMode");
    }
}
