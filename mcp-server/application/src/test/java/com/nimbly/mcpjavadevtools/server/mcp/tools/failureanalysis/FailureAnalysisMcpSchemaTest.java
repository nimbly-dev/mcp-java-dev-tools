package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FailureAnalysisMcpSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void advertisesCorrelatedActionsAndBothVerificationVariants() throws Exception {
        JsonNode schema = objectMapper.readTree(
                FailureAnalysisMcpSchema.publicInputSchema(objectMapper));

        assertThat(schema.path("oneOf")).hasSize(2);
        assertThat(schema.path("oneOf").get(0).path("properties").path("action").path("const").asText())
                .isEqualTo("analyze_trace");
        JsonNode verify = schema.path("oneOf").get(1).path("properties").path("input");
        assertThat(verify.path("oneOf")).hasSize(2);
        assertThat(verify.path("oneOf").get(0).path("required"))
                .isEqualTo(objectMapper.readTree(
                        "[\"captureId\",\"expectedFingerprint\",\"lineHit\",\"sidecarBaseUrl\"]"));
        assertThat(verify.path("oneOf").get(1).path("required"))
                .isEqualTo(objectMapper.readTree("[\"terminalState\"]"));
    }
}
