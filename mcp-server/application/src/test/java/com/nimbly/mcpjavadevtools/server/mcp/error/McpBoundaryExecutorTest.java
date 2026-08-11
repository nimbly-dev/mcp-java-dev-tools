package com.nimbly.mcpjavadevtools.server.mcp.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class McpBoundaryExecutorTest {

    @Test
    void mapsUnexpectedResponseMappingFailureToTheCallerResponseType() {
        McpBoundaryExecutor executor = new McpBoundaryExecutor();

        Map<String, String> response = executor.mapResponse(
                () -> {
                    throw new IllegalStateException("token=secret");
                },
                failure -> Map.of(
                        "kind", failure.failureKind().value(),
                        "correlationId", failure.correlationId()));

        assertThat(response)
                .containsEntry("kind", "response_mapping")
                .doesNotContainValue("secret");
        assertThat(response.get("correlationId")).isNotBlank();
    }
}
