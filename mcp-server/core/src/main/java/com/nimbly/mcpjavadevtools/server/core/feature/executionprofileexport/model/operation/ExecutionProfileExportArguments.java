package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.operation;

import java.util.Map;

/** Canonical export arguments without the internal Core action discriminator. */
public record ExecutionProfileExportArguments(
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

    public ExecutionProfileExportArguments {
        contextBindings = contextBindings == null ? Map.of() : Map.copyOf(contextBindings);
        contextValues = contextValues == null ? Map.of() : Map.copyOf(contextValues);
    }
}
