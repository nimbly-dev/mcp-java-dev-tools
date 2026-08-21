package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Thin Spring AI Application Adapter for the complete transport_execute Tool. */
@Component
public class TransportExecuteMcpTool {

    private final TransportExecutionFeature feature;
    private final TransportExecuteMcpRequestMapper requestMapper;
    private final TransportExecuteMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;
    private final ObjectMapper objectMapper;

    /** Creates the adapter from the intentional Core Feature boundary. */
    @Autowired
    public TransportExecuteMcpTool(TransportExecutionFeature feature) {
        this(feature, new TransportExecuteMcpRequestMapper(), new TransportExecuteMcpResponseMapper(),
                new McpBoundaryExecutor(), new ObjectMapper());
    }

    TransportExecuteMcpTool(
            TransportExecutionFeature feature,
            TransportExecuteMcpRequestMapper requestMapper,
            TransportExecuteMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor,
            ObjectMapper objectMapper) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
        this.objectMapper = objectMapper;
    }

    /** Exposes the unchanged protocol/request/options public MCP shape. */
    @McpTool(
            name = "transport_execute",
            description = "Execute a transport request through the MCP wrapper with fail-closed policy enforcement.",
            generateOutputSchema = false)
    public McpActionResponse execute(
            @McpToolParam(description = "Transport protocol.")
            String protocol,
            @McpToolParam(description = "Protocol-specific execution request payload.") Map<String, Object> request,
            @McpToolParam(description = "Optional transport execution policy.") @Nullable
            TransportExecuteMcpOptions options) {
        return invokeMcpRequest(new TransportExecuteMcpInput(protocol, request, options));
    }

    McpActionResponse invokeMcpRequest(TransportExecuteMcpInput input) {
        try {
            ExecuteTransportRequest coreRequest = requestMapper.map(input);
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
            Map<String, Object> payload = objectMapper.convertValue(
                    request.path("request"), new TypeReference<>() { });
            TransportExecuteMcpOptions options = request.has("options")
                    ? objectMapper.treeToValue(request.path("options"), TransportExecuteMcpOptions.class)
                    : null;
            return objectMapper.writeValueAsString(invokeMcpRequest(new TransportExecuteMcpInput(
                    request.path("protocol").asText(null), payload, options)));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return serialize(responseMapper.invalidRequest());
        }
    }

    private McpActionResponse invoke(ExecuteTransportRequest request) {
        try {
            ExecuteTransportResult result = feature.execute(request);
            return boundaryExecutor.mapResponse(
                    () -> responseMapper.map(request, result), responseMapper::mapBoundary);
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(new McpBoundaryException(
                    McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT, exception), responseMapper::mapBoundary);
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
