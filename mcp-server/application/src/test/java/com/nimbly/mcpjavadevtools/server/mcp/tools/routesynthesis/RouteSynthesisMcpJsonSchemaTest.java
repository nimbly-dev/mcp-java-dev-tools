package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteSynthesisMcpJsonSchemaTest {

    @Test
    void nativeMcpToolCarriesCorrelatedSchemaWithoutSdkLoss() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schema = mapper.readValue(
                RouteSynthesisMcpSchema.publicInputSchema(mapper), new TypeReference<>() { });
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("route_synthesis")
                .inputSchema(schema)
                .build();

        String serialized = mapper.writeValueAsString(tool.inputSchema());

        assertThat(serialized).contains("\"oneOf\"");
        assertThat(serialized).contains("\"const\":\"infer_target\"");
    }
}
