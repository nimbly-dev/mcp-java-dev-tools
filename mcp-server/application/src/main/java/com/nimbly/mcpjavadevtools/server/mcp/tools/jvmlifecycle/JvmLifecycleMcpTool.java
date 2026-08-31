package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin Spring AI Application Adapter for the complete JVM lifecycle MCP Tool.
 */
@Component
public final class JvmLifecycleMcpTool {

    /** Stable MCP Tool name consumed by the JVM lifecycle operation catalog. */
    public static final String TOOL_NAME = JvmLifecycleOperationCatalog.TOOL_NAME;

    private final JvmLifecycleFeature feature;
    private final JvmLifecycleMcpRequestMapper requestMapper;
    private final JvmLifecycleMcpResponseMapper responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;

    /** Creates the adapter from the intentional Core Feature boundary. */
    @Autowired
    public JvmLifecycleMcpTool(JvmLifecycleFeature feature) {
        this(feature, new JvmLifecycleMcpRequestMapper(), new JvmLifecycleMcpResponseMapper(),
                new McpBoundaryExecutor());
    }

    JvmLifecycleMcpTool(
            JvmLifecycleFeature feature,
            JvmLifecycleMcpRequestMapper requestMapper,
            JvmLifecycleMcpResponseMapper responseMapper,
            McpBoundaryExecutor boundaryExecutor) {
        this.feature = feature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
    }

    /** Describes the exact Spring AI registration consumed by the Core catalog. */
    public static OperationExposure operationExposure() {
        return new OperationExposure(
                TOOL_NAME,
                JvmLifecycleMcpTool.class.getName(),
                List.of("list_jvms", "attach", "deactivate"));
    }

    /**
     * Exposes the complete action-based lifecycle contract through Spring AI.
     *
     * @param action one of list_jvms, attach, or deactivate
     * @param input action-specific input
     * @return deterministic lifecycle response
     */
    @McpTool(
            name = TOOL_NAME,
            description = "Discover local JVMs and safely attach or deactivate the repository-owned Sidecar Agent.",
            generateOutputSchema = true)
    public McpActionResponse execute(
            @McpToolParam(description = "Required JVM lifecycle action.") String action,
            @McpToolParam(description = "Action-specific JVM lifecycle input.") JvmLifecycleMcpActionInput input) {
        return invokeMcpRequest(new McpActionRequest<>(action, input));
    }

    McpActionResponse invokeMcpRequest(McpActionRequest<JvmLifecycleMcpActionInput> request) {
        try {
            JvmLifecycleRequest coreRequest = mapRequest(request);
            if (coreRequest == null) {
                return responseMapper.invalidRequest();
            }
            return invoke(coreRequest);
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        }
    }

    private JvmLifecycleRequest mapRequest(
            McpActionRequest<JvmLifecycleMcpActionInput> request) {
        try {
            return requestMapper.map(request);
        } catch (IllegalArgumentException exception) {
            return null;
        } catch (RuntimeException exception) {
            throw new McpBoundaryException(
                    com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind.REQUEST_MAPPING,
                    exception);
        }
    }

    private McpActionResponse invoke(JvmLifecycleRequest request) {
        try {
            JvmLifecycleResult result = feature.execute(request);
            return boundaryExecutor.mapResponse(
                    () -> responseMapper.map(request, result), responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(
                    new McpBoundaryException(
                            com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT,
                            exception),
                    responseMapper::mapBoundary);
        }
    }
}
