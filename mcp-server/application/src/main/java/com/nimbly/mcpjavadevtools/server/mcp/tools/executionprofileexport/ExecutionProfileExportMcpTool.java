package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;

/** Thin Spring AI Application Adapter for execution_profile_export. */
public final class ExecutionProfileExportMcpTool {

    public static final String TOOL_NAME = ExecutionProfileExportOperationCatalog.TOOL_NAME;
    private static final String SERIALIZATION_FALLBACK =
            "{\"resultType\":\"report\",\"status\":\"internal_error\","
                    + "\"reasonCode\":\"internal_error\"}";

    private final ExecutionProfileExportFeature feature;
    private final ExecutionProfileExportMcpRequestMapper requestMapper;
    private final McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult> responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;
    private final ObjectMapper objectMapper;

    /** Creates the adapter from the intentional Core Feature boundary. */
    @Autowired
    public ExecutionProfileExportMcpTool(ExecutionProfileExportFeature feature, ObjectMapper objectMapper) {
        this(feature, new ExecutionProfileExportMcpRequestMapper(),
                new ExecutionProfileExportMcpResponseMapper(), new McpBoundaryExecutor(), objectMapper);
    }

    ExecutionProfileExportMcpTool(
            ExecutionProfileExportFeature feature,
            ExecutionProfileExportMcpRequestMapper requestMapper,
            McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult> responseMapper,
            McpBoundaryExecutor boundaryExecutor,
            ObjectMapper objectMapper) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
        this.objectMapper = objectMapper;
    }

    /** Describes the exact MCP registration consumed by the operation catalog. */
    public static OperationExposure operationExposure() {
        return new OperationExposure(
                TOOL_NAME,
                ExecutionProfileExportMcpTool.class.getName(),
                List.of(ExecutionProfileExportOperationCatalog.ACTION));
    }

    /** Exposes the complete TypeScript-compatible input contract. */
    @McpTool(
            name = TOOL_NAME,
            description = "Export one persisted Execution Profile into deterministic replay artifacts.",
            generateOutputSchema = false)
    public McpActionResponse execute(
            @McpToolParam(description = "Execution Profile Export request.") ExecutionProfileExportMcpRequest request) {
        try {
            ExecutionProfileExportRequest coreRequest = requestMapper.map(request);
            ExecutionProfileExportResult result = feature.execute(coreRequest);
            try {
                return responseMapper.map(coreRequest, result);
            } catch (McpBoundaryException exception) {
                return boundaryExecutor.map(exception, responseMapper::mapBoundary);
            } catch (RuntimeException exception) {
                return boundaryExecutor.map(
                        new McpBoundaryException(McpBoundaryFailureKind.RESPONSE_MAPPING, exception),
                        responseMapper::mapBoundary);
            }
        } catch (IllegalArgumentException exception) {
            return boundaryExecutor.mapResponse(
                    responseMapper::invalidRequest,
                    responseMapper::mapBoundary);
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
        ExecutionProfileExportMcpRequest request;
        try {
            request = objectMapper.readValue(
                    arguments, ExecutionProfileExportMcpRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            try {
                return objectMapper.writeValueAsString(boundaryExecutor.mapResponse(
                        responseMapper::invalidRequest,
                        responseMapper::mapBoundary));
            } catch (JsonProcessingException serializationException) {
                return SERIALIZATION_FALLBACK;
            }
        }
        try {
            return objectMapper.writeValueAsString(execute(request));
        } catch (JsonProcessingException exception) {
            return SERIALIZATION_FALLBACK;
        }
    }
}
