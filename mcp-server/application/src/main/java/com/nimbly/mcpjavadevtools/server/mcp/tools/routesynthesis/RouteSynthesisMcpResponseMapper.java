package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps Core Route Synthesis results into the stable MCP action envelope. */
class RouteSynthesisMcpResponseMapper
        implements McpActionResponseMapper<RouteSynthesisRequest, RouteSynthesisResult> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    RouteSynthesisMcpResponseMapper() {
        this(new ObjectMapper());
    }

    RouteSynthesisMcpResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public McpActionResponse map(RouteSynthesisRequest request, RouteSynthesisResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (result.actionResult() != null) {
            details.putAll(objectMapper.convertValue(result.actionResult(), MAP_TYPE));
        }
        if (!result.evidence().isEmpty()) {
            details.put("evidence", result.evidence());
        }
        if (!result.attemptedStrategies().isEmpty()) {
            details.put("attemptedStrategies", result.attemptedStrategies());
        }
        Map<String, Object> reasonMeta = new LinkedHashMap<>();
        if (result.failedStep() != null) {
            reasonMeta.put("failedStep", result.failedStep());
        }
        if (!result.evidence().isEmpty()) {
            reasonMeta.put("evidence", result.evidence());
        }
        return new McpActionResponse(
                result.resultType(), result.status(), result.reasonCode(), result.nextActionCode(),
                result.nextAction(), "", reasonMeta, result.actionResult(), details);
    }

    @Override
    public McpActionResponse invalidRequest() {
        return new McpActionResponse(
                "report", "blocked_invalid", "invalid_request", "invalid_request",
                "Provide a valid Route Synthesis action and input.", "", Map.of(), null, Map.of());
    }

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
