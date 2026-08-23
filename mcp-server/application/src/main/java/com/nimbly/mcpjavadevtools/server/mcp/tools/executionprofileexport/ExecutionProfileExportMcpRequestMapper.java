package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the transport request into the Spring-independent Feature request. */
public final class ExecutionProfileExportMcpRequestMapper {

    /** Performs structural mapping and defensive context-map copying. */
    public ExecutionProfileExportRequest map(ExecutionProfileExportMcpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Execution Profile Export request is required");
        }
        return new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT,
                request.projectName(), request.exportId(), request.executionProfile(), request.planName(),
                request.when(), request.mode(), request.type(), request.includeResolvedSecrets(),
                request.includeRuntimeStartup(), request.includeHealthcheckGate(),
                cleanMap(request.contextBindings()), cleanMap(request.contextValues()));
    }

    private Map<String, String> cleanMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                cleaned.put(key.trim(), value);
            }
        });
        return cleaned;
    }
}
