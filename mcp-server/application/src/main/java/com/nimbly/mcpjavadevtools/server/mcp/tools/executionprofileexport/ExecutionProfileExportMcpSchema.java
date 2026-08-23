package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact public input schema for the execution_profile_export MCP Tool. */
public final class ExecutionProfileExportMcpSchema {

    private ExecutionProfileExportMcpSchema() {
    }

    /** Returns the TypeScript-compatible top-level input contract. */
    public static Map<String, Object> publicInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        putString(properties, "projectName");
        putString(properties, "exportId");
        putString(properties, "executionProfile");
        putString(properties, "planName");
        putString(properties, "when");
        properties.put("mode", enumProperty());
        properties.put("type", enumProperty());
        putBoolean(properties, "includeResolvedSecrets");
        putBoolean(properties, "includeRuntimeStartup");
        putBoolean(properties, "includeHealthcheckGate");
        properties.put("contextBindings", stringMapProperty());
        properties.put("contextValues", stringMapProperty());
        return Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false);
    }

    private static Map<String, Object> enumProperty() {
        return Map.of("type", "string", "enum", List.of("ps1", "sh", "postman"));
    }

    private static Map<String, Object> stringMapProperty() {
        return Map.of("type", "object", "additionalProperties", Map.of("type", "string"));
    }

    private static void putString(Map<String, Object> properties, String name) {
        properties.put(name, Map.of("type", "string"));
    }

    private static void putBoolean(Map<String, Object> properties, String name) {
        properties.put(name, Map.of("type", "boolean"));
    }
}
