package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable Core request for one Execution Profile Export operation. */
public record ExecutionProfileExportRequest(
        ExecutionProfileExportAction action,
        String projectName,
        String exportId,
        String executionProfile,
        String planName,
        String when,
        String mode,
        String type,
        Boolean includeResolvedSecrets,
        Boolean includeRuntimeStartup,
        Boolean includeHealthcheckGate,
        Map<String, String> contextBindings,
        Map<String, String> contextValues) {

    /** Defensively copies caller-owned context maps. */
    public ExecutionProfileExportRequest {
        contextBindings = copy(contextBindings);
        contextValues = copy(contextValues);
    }

    private static Map<String, String> copy(Map<String, String> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
