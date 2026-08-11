package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/**
 * Parses only object-shaped bounded Sidecar profiler payloads.
 */
class ProbeProfilerPayloadParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeProfilerPayloadParser() {
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
