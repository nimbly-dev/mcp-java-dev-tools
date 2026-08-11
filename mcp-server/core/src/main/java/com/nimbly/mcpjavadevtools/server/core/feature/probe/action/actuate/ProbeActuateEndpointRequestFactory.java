package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates bounded JSON requests for the existing Sidecar actuation endpoint.
 */
class ProbeActuateEndpointRequestFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeActuateEndpointRequestFactory() {
    }

    static ProbeEndpointRequest create(
            ProbeTarget target,
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeActuateRequest request) {
        URI endpoint = target.baseUrl().resolve(endpointConfiguration.paths().actuatePath());
        return new ProbeEndpointRequest(
                endpoint,
                "POST",
                Map.of("content-type", "application/json"),
                payload(request),
                endpointConfiguration.requestPolicy().timeoutOrDefault(request.timeout()),
                endpointConfiguration);
    }

    private static String payload(ProbeActuateRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", request.command().value());
        values.put("sessionId", request.sessionId());
        if (request.actuatorId() != null) {
            values.put("actuatorId", request.actuatorId());
        }
        if (request.targetKey() != null) {
            values.put("targetKey", request.targetKey());
        }
        if (request.returnBoolean() != null) {
            values.put("returnBoolean", request.returnBoolean());
        }
        if (request.ttlMs() != null) {
            values.put("ttlMs", request.ttlMs());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Probe actuation payload could not be serialized", exception);
        }
    }
}
