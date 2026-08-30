package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.Map;

/** Maps Core Deterministic Output into the stable MCP response envelope. */
public final class ExecutionProfileExportMcpResponseMapper
        implements McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult> {

    /** Maps the typed Core request and result without adding capability behavior. */
    @Override
    public McpActionResponse map(ExecutionProfileExportRequest request, ExecutionProfileExportResult result) {
        return map(result);
    }

    /** Maps the Feature result without adding product behavior. */
    public McpActionResponse map(ExecutionProfileExportResult result) {
        return new McpActionResponse(
                result.resultType(), result.status(), result.reasonCode(), result.nextActionCode(),
                result.nextAction(), result.reason(), result.reasonMeta(), null, result.details());
    }

    /** Creates the deterministic invalid-request result. */
    @Override
    public McpActionResponse invalidRequest() {
        return map(ExecutionProfileExportResult.invalidRequest());
    }

    /** Contains unexpected Application-boundary failures. */
    @Override
    public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report", "internal_error", "internal_error", "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of("correlationId", failure.correlationId(), "failedStep", failure.failureKind().value()),
                null, Map.of());
    }
}
