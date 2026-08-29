package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads scalar JSON object fields into deterministic string maps. */
final class ExecutionExportScalarValues {

    private ExecutionExportScalarValues() {
    }

    static Map<String, String> from(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    values.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return values;
    }
}
