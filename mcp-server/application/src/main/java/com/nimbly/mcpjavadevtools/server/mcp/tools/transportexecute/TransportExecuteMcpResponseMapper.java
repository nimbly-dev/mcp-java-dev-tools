package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps Core transport outcomes into the stable MCP response envelope. */
final class TransportExecuteMcpResponseMapper {

    McpActionResponse map(ExecuteTransportRequest request, ExecuteTransportResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "errorMessage", result.errorMessage());
        putIfPresent(details, "durationMs", result.durationMs());
        putIfPresent(details, "protocol", result.protocol());
        putIfPresent(details, "statusCode", result.statusCode());
        if (!result.headers().isEmpty() || result.statusCode() != null) {
            details.put("headers", result.headers());
        }
        putIfPresent(details, "bodyPreview", result.bodyPreview());
        return new McpActionResponse(
                "report",
                result.status(),
                result.reasonCode(),
                result.nextActionCode(),
                null,
                "",
                result.reasonMeta(),
                null,
                details);
    }

    McpActionResponse invalidRequest() {
        return map(null, ExecuteTransportResult.blockedInvalid(
                "transport_request_invalid",
                "transport_execute request is invalid.",
                null,
                1));
    }

    McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report",
                "internal_error",
                "internal_error",
                "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of("correlationId", failure.correlationId(), "failedStep", failure.failureKind().value()),
                null,
                Map.of());
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
