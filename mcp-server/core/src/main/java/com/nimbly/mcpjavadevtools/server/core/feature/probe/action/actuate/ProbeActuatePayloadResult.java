package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;
import java.util.Optional;

/**
 * Maps a validated Sidecar actuation envelope to a compact immutable result.
 */
class ProbeActuatePayloadResult {

    private ProbeActuatePayloadResult() {
    }

    static Optional<ProbeActuateResult> from(
            JsonNode root,
            ProbeActuateRequest request,
            ProbeResponseCompactionPolicy compactionPolicy) {
        if (!root.path("ok").isBoolean() || !root.path("ok").booleanValue()) {
            return Optional.empty();
        }
        JsonNode action = root.path("action");
        JsonNode sessionId = root.path("sessionId");
        if (!action.isTextual()
                || !request.command().value().equals(action.asText())
                || !sessionId.isTextual()
                || !request.sessionId().equals(sessionId.asText())) {
            return Optional.empty();
        }
        return Optional.of(new ProbeActuateResult(
                true,
                ProbeResponseTextCompactor.compact(action.asText(), compactionPolicy),
                root.path("mode").isTextual()
                        ? ProbeResponseTextCompactor.compact(root.path("mode").asText(), compactionPolicy) : null,
                ProbeResponseTextCompactor.compact(sessionId.asText(), compactionPolicy),
                root.path("actuatorId").isTextual()
                        ? ProbeResponseTextCompactor.compact(root.path("actuatorId").asText(), compactionPolicy) : null,
                root.path("targetKey").isTextual()
                        ? ProbeResponseTextCompactor.compact(root.path("targetKey").asText(), compactionPolicy) : null,
                root.path("returnBoolean").isBoolean() ? root.path("returnBoolean").booleanValue() : null,
                root.path("ttlMs").canConvertToLong() ? root.path("ttlMs").longValue() : null,
                root.path("expiresAtEpoch").canConvertToLong() ? root.path("expiresAtEpoch").longValue() : null,
                root.path("scopeState").isTextual()
                        ? ProbeResponseTextCompactor.compact(root.path("scopeState").asText(), compactionPolicy) : null,
                null));
    }
}
