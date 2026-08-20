package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import java.util.Optional;

/** Immutable Core request envelope for the consolidated Artifact Management MCP Tool. */
public record ArtifactManagementRequest(
        ArtifactType artifactType,
        ArtifactAction action,
        JsonNode input) {

    /** Normalizes an absent input object to a JSON null value. */
    public ArtifactManagementRequest {
        input = input == null ? NullNode.getInstance() : input;
    }

    /** Reads one optional textual input field without performing I/O. */
    public Optional<String> text(String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText().trim());
    }

    /** Reads one optional JSON child object or value. */
    public Optional<JsonNode> child(String field) {
        JsonNode value = input.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }

    /** Reads one optional boolean input field. */
    public Optional<Boolean> booleanValue(String field) {
        JsonNode value = input.get(field);
        return value != null && value.isBoolean() ? Optional.of(value.booleanValue()) : Optional.empty();
    }

    /** Reads one optional bounded integer input field. */
    public Optional<Integer> integerValue(String field) {
        JsonNode value = input.get(field);
        return value != null && value.canConvertToInt() ? Optional.of(value.intValue()) : Optional.empty();
    }
}
