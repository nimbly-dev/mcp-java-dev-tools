package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Thin Spring AI Application Adapter for execution_profile_export. */
@Component
public final class ExecutionProfileExportMcpTool {

    private final ExecutionProfileExportFeature feature;
    private final ExecutionProfileExportMcpRequestMapper requestMapper;
    private final ExecutionProfileExportMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;
    private final ObjectMapper objectMapper;

    /** Creates the adapter from the intentional Core Feature boundary. */
    @Autowired
    public ExecutionProfileExportMcpTool(ExecutionProfileExportFeature feature) {
        this(feature, new ExecutionProfileExportMcpRequestMapper(),
                new ExecutionProfileExportMcpResponseMapper(), new McpBoundaryExecutor(), new ObjectMapper());
    }

    ExecutionProfileExportMcpTool(
            ExecutionProfileExportFeature feature,
            ExecutionProfileExportMcpRequestMapper requestMapper,
            ExecutionProfileExportMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor,
            ObjectMapper objectMapper) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
        this.objectMapper = objectMapper;
    }

    /** Exposes the complete TypeScript-compatible input contract. */
    @McpTool(
            name = "execution_profile_export",
            description = "Export one persisted Execution Profile into deterministic replay artifacts.",
            generateOutputSchema = false)
    public McpActionResponse execute(
            @McpToolParam(description = "Execution Profile Export request.") ExecutionProfileExportMcpRequest request) {
        return invokeMcpRequest(request);
    }

    /** Package-visible entry point for focused adapter tests. */
    McpActionResponse invokeMcpRequest(ExecutionProfileExportMcpRequest request) {
        try {
            ExecutionProfileExportRequest coreRequest = requestMapper.map(request);
            ExecutionProfileExportResult result = feature.execute(coreRequest);
            return boundaryExecutor.mapResponse(
                    () -> responseMapper.map(result), responseMapper::mapBoundary);
        } catch (IllegalArgumentException exception) {
            return responseMapper.invalidRequest();
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(
                    new McpBoundaryException(McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT, exception),
                    responseMapper::mapBoundary);
        }
    }

    /** Raw JSON helper used by deterministic adapter tests. */
    public String call(String arguments) {
        try {
            ExecutionProfileExportMcpRequest request = objectMapper.readValue(
                    arguments, ExecutionProfileExportMcpRequest.class);
            return objectMapper.writeValueAsString(invokeMcpRequest(request));
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
}
