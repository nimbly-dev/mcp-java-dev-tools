package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin Spring AI adapter for the complete Route Synthesis MCP Tool.
 *
 * <p>The annotated method supplies the standard Spring AI MCP binding. Application
 * composition also supplements that binding with the native correlated schema,
 * because the SDK's generated carrier cannot preserve the action/input
 * {@code oneOf} relationship.</p>
 */
@Component
public class RouteSynthesisMcpTool {

    private final RouteSynthesisFeature feature;
    private final RouteSynthesisMcpRequestMapper requestMapper;
    private final RouteSynthesisMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;
    private final ObjectMapper objectMapper;

    RouteSynthesisMcpTool(
            RouteSynthesisFeature feature,
            RouteSynthesisMcpRequestMapper requestMapper,
            RouteSynthesisMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor,
            ObjectMapper objectMapper) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
        this.objectMapper = objectMapper;
    }

    /** Creates the adapter from the complete Core Feature boundary. */
    @Autowired
    public RouteSynthesisMcpTool(RouteSynthesisFeature feature) {
        this(feature, new RouteSynthesisMcpRequestMapper(), new RouteSynthesisMcpResponseMapper(),
                new McpBoundaryExecutor(), new ObjectMapper());
    }

    /** Exposes the consolidated route synthesis contract through Spring AI. */
    @McpTool(
            name = "route_synthesis",
            description = "Infer Java HTTP targets, discover handlers, and create bounded recipes.",
            generateOutputSchema = false)
    public McpActionResponse execute(
            @McpToolParam(description = "Required consolidated Route Synthesis action.") String action,
            @McpToolParam(description = "Action-specific Route Synthesis input.") RouteSynthesisMcpActionInput input) {
        return invokeMcpRequest(new McpActionRequest<>(action, input));
    }

    /** Exposes all four approved Route Synthesis actions through one MCP Tool. */
    public McpActionResponse execute(RouteSynthesisMcpAction action, RouteSynthesisMcpActionInput input) {
        return invokeMcpRequest(new McpActionRequest<>(action.value(), input));
    }

    public String call(String arguments) {
        try {
            JsonNode request = objectMapper.readTree(arguments);
            RouteSynthesisMcpAction action = objectMapper.treeToValue(
                    request.path("action"), RouteSynthesisMcpAction.class);
            RouteSynthesisMcpActionInput input = objectMapper.treeToValue(
                    request.path("input"), RouteSynthesisMcpActionInput.class);
            return objectMapper.writeValueAsString(execute(action, input));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return serialize(responseMapper.invalidRequest());
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

    private McpActionResponse invokeMcpRequest(McpActionRequest<RouteSynthesisMcpActionInput> request) {
        try {
            RouteSynthesisRequest coreRequest = requestMapper.map(request);
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

    private McpActionResponse invoke(RouteSynthesisRequest request) {
        try {
            RouteSynthesisResult result = feature.execute(request);
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
}
