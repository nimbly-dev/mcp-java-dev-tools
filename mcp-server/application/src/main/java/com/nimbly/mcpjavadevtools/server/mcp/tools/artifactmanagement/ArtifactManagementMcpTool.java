package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import java.util.Arrays;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Thin Spring AI Application Adapter for the complete Artifact Management MCP Tool. */
@Component
public final class ArtifactManagementMcpTool {

    /** Stable public MCP Tool name shared with the Core operation catalog. */
    public static final String TOOL_NAME = ArtifactOperationCatalog.TOOL_NAME;

    private final ArtifactManagementFeature feature;
    private final ArtifactManagementMcpRequestMapper requestMapper;
    private final ArtifactManagementMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;

    /** Creates the adapter from the intentional Core Feature boundary. */
    @Autowired
    public ArtifactManagementMcpTool(
            ArtifactManagementFeature feature,
            ObjectMapper mapper) {
        this(feature, new ArtifactManagementMcpRequestMapper(mapper),
                new ArtifactManagementMcpResponseMapper(), new McpBoundaryExecutor());
    }

    ArtifactManagementMcpTool(
            ArtifactManagementFeature feature,
            ArtifactManagementMcpRequestMapper requestMapper,
            ArtifactManagementMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
    }

    /** Describes the complete nested family/action registration consumed by Core. */
    public static OperationExposure operationExposure() {
        return new OperationExposure(
                TOOL_NAME,
                ArtifactManagementMcpTool.class.getName(),
                Arrays.stream(ArtifactManagementAction.values())
                        .map(ArtifactManagementAction::routeId)
                        .toList());
    }

    /** Exposes the complete Artifact family/action contract through Spring AI. */
    @McpTool(
            name = TOOL_NAME,
            description = "Manage persisted .mcpjvm Artifacts with strict action allowlists and deterministic Fail-Closed output.",
            generateOutputSchema = true)
    public McpActionResponse execute(
            @McpToolParam(description = "Artifact family under .mcpjvm.") String artifactType,
            @McpToolParam(description = "Artifact action allowed for the selected family.") String action,
            @McpToolParam(description = "Action-specific Artifact input.") Map<String, Object> input) {
        return execute(new ArtifactManagementMcpRequest(artifactType, action, input));
    }

    McpActionResponse execute(ArtifactManagementMcpRequest request) {
        try {
            ArtifactManagementRequest coreRequest = requestMapper.map(request);
            ArtifactManagementResult result = feature.execute(coreRequest);
            return boundaryExecutor.mapResponse(() -> responseMapper.map(result), responseMapper::mapBoundary);
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
}
