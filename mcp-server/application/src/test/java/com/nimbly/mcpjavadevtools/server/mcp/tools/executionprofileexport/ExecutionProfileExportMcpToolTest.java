package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionProfileExportMcpToolTest {

    @Test
    void mapsThePublicRequestAndResponseWithoutAddingCapabilityBehavior() {
        ExecutionProfileExportFeature feature = request -> new ExecutionProfileExportResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                Map.of("mode", request.mode(), "exportId", "export-1"));
        ExecutionProfileExportMcpTool tool = new ExecutionProfileExportMcpTool(
                feature, new ExecutionProfileExportMcpRequestMapper(),
                new ExecutionProfileExportMcpResponseMapper(),
                new com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        McpActionResponse response = tool.invokeMcpRequest(new ExecutionProfileExportMcpRequest(
                "demo", null, "nightly", null, null, "sh", null, false, true, true,
                Map.of("auth.bearer", "AUTH_TOKEN"), Map.of()));

        assertThat(response.resultType()).isEqualTo("execution_profile_export");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.details()).containsEntry("mode", "sh");
        assertThat(response.details()).containsEntry("exportId", "export-1");
    }

    @Test
    void returnsDeterministicInvalidRequestForNullTransportInput() {
        ExecutionProfileExportFeature feature = request -> ExecutionProfileExportResult.invalidRequest();
        ExecutionProfileExportMcpTool tool = new ExecutionProfileExportMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(null);

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }
}
