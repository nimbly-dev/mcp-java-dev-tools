package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Thin Spring AI Application Adapter for the complete failure_analysis Tool. */
@Component
public class FailureAnalysisMcpTool {

    private final FailureAnalysisFeature feature;
    private final FailureAnalysisMcpRequestMapper requestMapper;
    private final FailureAnalysisMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public FailureAnalysisMcpTool(FailureAnalysisFeature feature) {
        this(feature, new FailureAnalysisMcpRequestMapper(), new FailureAnalysisMcpResponseMapper(),
                new McpBoundaryExecutor(), new ObjectMapper());
    }

    FailureAnalysisMcpTool(
            FailureAnalysisFeature feature,
            FailureAnalysisMcpRequestMapper requestMapper,
            FailureAnalysisMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor,
            ObjectMapper objectMapper) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
        this.objectMapper = objectMapper;
    }

    /** Exposes both Failure Analysis actions through one stable MCP Tool. */
    @McpTool(
            name = "failure_analysis",
            description = "Analyze a pasted Java failure and verify bounded runtime reproduction evidence.",
            generateOutputSchema = false)
    public McpActionResponse execute(
            @McpToolParam(description = "Required consolidated Failure Analysis action.") String action,
            @McpToolParam(description = "Action-specific Failure Analysis input.")
            FailureAnalysisMcpActionInput input) {
        return invokeMcpRequest(new McpActionRequest<>(action, input));
    }

    /** Package-visible entry point for focused Application tests. */
    McpActionResponse invokeMcpRequest(McpActionRequest<FailureAnalysisMcpActionInput> request) {
        try {
            FailureAnalysisRequest coreRequest = requestMapper.map(request);
            return invoke(coreRequest);
        } catch (IllegalArgumentException exception) {
            return responseMapper.invalidRequest();
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(new McpBoundaryException(
                    McpBoundaryFailureKind.REQUEST_MAPPING, exception), responseMapper::mapBoundary);
        }
    }

    /** Raw JSON helper used by deterministic adapter tests. */
    public String call(String arguments) {
        try {
            JsonNode request = objectMapper.readTree(arguments);
            String action = request.path("action").asText(null);
            FailureAnalysisMcpActionInput input = objectMapper.treeToValue(
                    request.path("input"), FailureAnalysisMcpActionInput.class);
            return objectMapper.writeValueAsString(
                    invokeMcpRequest(new McpActionRequest<>(action, input)));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return serialize(responseMapper.invalidRequest());
        }
    }

    private McpActionResponse invoke(FailureAnalysisRequest request) {
        try {
            FailureAnalysisResult result = feature.execute(request);
            return boundaryExecutor.mapResponse(
                    () -> responseMapper.map(request, result), responseMapper::mapBoundary);
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(new McpBoundaryException(
                    McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT, exception),
                    responseMapper::mapBoundary);
        }
    }

    private String serialize(McpActionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            return "{\"resultType\":\"report\",\"status\":\"internal_error\","
                    + "\"reasonCode\":\"internal_error\"}";
        }
    }
}
