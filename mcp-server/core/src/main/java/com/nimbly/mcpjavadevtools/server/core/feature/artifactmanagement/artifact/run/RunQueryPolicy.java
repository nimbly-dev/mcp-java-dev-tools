package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.Optional;

/** Bounded input policy for run-state retention and query options. */
final class RunQueryPolicy {
    private RunQueryPolicy() { }

    static Optional<Boolean> optionalBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? Optional.of(value.booleanValue()) : Optional.empty();
    }

    static int boundedInteger(JsonNode node, String field, int defaultValue, int minimum, int maximum) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (!value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum) {
            throw new ArtifactOperationException("retention_policy_invalid",
                    field + " is outside its bounded range");
        }
        return value.intValue();
    }
}
