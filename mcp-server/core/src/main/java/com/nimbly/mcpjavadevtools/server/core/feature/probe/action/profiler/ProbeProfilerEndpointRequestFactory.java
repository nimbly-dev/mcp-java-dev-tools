package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates command-specific bounded Sidecar profiler requests.
 */
class ProbeProfilerEndpointRequestFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeProfilerEndpointRequestFactory() {
    }

    static ProbeEndpointRequest create(
            ProbeTarget target,
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeProfilerRequest request) {
        URI path = target.baseUrl().resolve(endpointConfiguration.paths().profilerPath());
        URI endpoint = path;
        if (request.command() == ProbeProfilerCommand.DOWNLOAD) {
            String query = "action=download";
            if (request.sessionId() != null) {
                query += "&sessionId=" + URLEncoder.encode(request.sessionId(), StandardCharsets.UTF_8);
            }
            endpoint = URI.create(path + "?" + query);
        }
        boolean readOnly = request.command() == ProbeProfilerCommand.STATUS
                || request.command() == ProbeProfilerCommand.DOWNLOAD;
        String method = readOnly ? "GET" : "POST";
        return new ProbeEndpointRequest(
                endpoint,
                method,
                readOnly ? Map.of() : Map.of("content-type", "application/json"),
                readOnly ? "" : payload(request),
                endpointConfiguration.requestPolicy().timeoutOrDefault(request.timeout()),
                endpointConfiguration);
    }

    private static String payload(ProbeProfilerRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", request.command().value());
        if (request.provider() != null) {
            values.put("provider", request.provider().value());
        }
        if (request.sessionId() != null) {
            values.put("sessionId", request.sessionId());
        }
        if (request.event() != null) {
            values.put("event", request.event());
        }
        if (request.intervalNanos() != null) {
            values.put("intervalNanos", request.intervalNanos());
        }
        if (request.outputPath() != null) {
            values.put("outputPath", request.outputPath());
        }
        if (request.outputFormat() != null) {
            values.put("outputFormat", request.outputFormat());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Probe profiler payload could not be serialized", exception);
        }
    }
}
