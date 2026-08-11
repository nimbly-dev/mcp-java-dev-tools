package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/**
 * Parses only object-shaped bounded Sidecar actuation payloads.
 */
class ProbeActuatePayloadParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeActuatePayloadParser() {
    }

    static Optional<JsonNode> object(String payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
