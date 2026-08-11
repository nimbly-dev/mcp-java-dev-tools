package com.nimbly.mcpjavadevtools.server.mcp.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpBoundaryExceptionMapperTest {

    @Test
    void mapsEveryClosedFailureKindToANeutralSanitizedFailure() {
        McpBoundaryExceptionMapper mapper = new McpBoundaryExceptionMapper();

        for (McpBoundaryFailureKind failureKind : McpBoundaryFailureKind.values()) {
            McpBoundaryFailure failure = mapper.map(new McpBoundaryException(
                    failureKind,
                    new IllegalStateException("token=secret")));

            assertThat(failure.failureKind()).isEqualTo(failureKind);
            assertThat(failure.correlationId()).isNotBlank();
        }
    }
}
