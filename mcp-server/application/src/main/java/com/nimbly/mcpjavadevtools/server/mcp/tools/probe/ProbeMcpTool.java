package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequestMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin Spring AI Application Adapter for the complete consolidated Probe MCP Tool.
 */
@Component
public class ProbeMcpTool {

    private final ProbeFeature probeFeature;
    private final McpActionRequestMapper<ProbeMcpActionInput, ProbeRequest> requestMapper;
    private final McpActionResponseMapper<ProbeRequest, ProbeResult> responseMapper;
    private final McpBoundaryExecutor boundaryExecutor;

    /**
     * Creates the Application Adapter from its intentional collaborators.
     *
     * @param probeFeature complete Spring-independent Core Feature
     */
    @Autowired
    public ProbeMcpTool(ProbeFeature probeFeature) {
        this(probeFeature, new ProbeMcpRequestMapper(), new ProbeMcpResponseMapper(), new McpBoundaryExecutor());
    }

    ProbeMcpTool(
            ProbeFeature probeFeature,
            McpActionRequestMapper<ProbeMcpActionInput, ProbeRequest> requestMapper,
            McpActionResponseMapper<ProbeRequest, ProbeResult> responseMapper,
            McpBoundaryExecutor boundaryExecutor) {
        this.probeFeature = probeFeature;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.boundaryExecutor = boundaryExecutor;
    }

    /**
     * Maps MCP input to the Core Feature and normalizes only its deterministic outcome.
     *
     * @param action required consolidated Probe action
     * @param input action-specific MCP input
     * @return deterministic Probe MCP response
     */
    @McpTool(
            name = "probe",
            description = "Run a bounded Java Sidecar Probe action.",
            generateOutputSchema = true)
    public McpActionResponse probe(
            @McpToolParam(description = "Required consolidated Probe action.") String action,
            @McpToolParam(description = "Action-specific Probe input.") ProbeMcpActionInput input) {
        return invokeMcpRequest(new McpActionRequest<>(action, input));
    }

    McpActionResponse invokeMcpRequest(McpActionRequest<ProbeMcpActionInput> request) {
        try {
            ProbeRequest coreRequest = mapRequest(request);
            if (coreRequest == null) {
                return mapInvalidRequest();
            }
            return invoke(coreRequest);
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        }
    }

    private ProbeRequest mapRequest(McpActionRequest<ProbeMcpActionInput> request) {
        try {
            return requestMapper.map(request);
        } catch (IllegalArgumentException exception) {
            return null;
        } catch (RuntimeException exception) {
            throw new McpBoundaryException(McpBoundaryFailureKind.REQUEST_MAPPING, exception);
        }
    }

    private McpActionResponse invoke(ProbeRequest request) {
        try {
            ProbeResult result = probeFeature.execute(request);
            return mapResponse(request, result);
        } catch (McpBoundaryException exception) {
            return boundaryExecutor.map(exception, responseMapper::mapBoundary);
        } catch (RuntimeException exception) {
            return boundaryExecutor.map(new McpBoundaryException(
                    McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT,
                    exception), responseMapper::mapBoundary);
        }
    }

    private McpActionResponse mapInvalidRequest() {
        return boundaryExecutor.mapResponse(responseMapper::invalidRequest, responseMapper::mapBoundary);
    }

    private McpActionResponse mapResponse(ProbeRequest request, ProbeResult result) {
        return boundaryExecutor.mapResponse(
                () -> responseMapper.map(request, result),
                responseMapper::mapBoundary);
    }

}
