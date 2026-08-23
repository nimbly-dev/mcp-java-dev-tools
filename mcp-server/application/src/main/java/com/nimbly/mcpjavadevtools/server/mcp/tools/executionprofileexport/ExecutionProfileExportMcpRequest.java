package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Transport-only request shape for the execution_profile_export MCP Tool. */
public record ExecutionProfileExportMcpRequest(
        @Nullable String projectName,
        @Nullable String exportId,
        @Nullable String executionProfile,
        @Nullable String planName,
        @Nullable String when,
        @Nullable String mode,
        @Nullable String type,
        @Nullable Boolean includeResolvedSecrets,
        @Nullable Boolean includeRuntimeStartup,
        @Nullable Boolean includeHealthcheckGate,
        @Nullable Map<String, String> contextBindings,
        @Nullable Map<String, String> contextValues) {
}
