package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Creates the bounded Sidecar capture lookup request.
 */
class ProbeCaptureEndpointRequestFactory {

    private ProbeCaptureEndpointRequestFactory() {
    }

    static ProbeEndpointRequest create(
            ProbeTarget target,
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeCaptureRequest request) {
        URI path = target.baseUrl().resolve(endpointConfiguration.paths().capturePath());
        URI endpoint = URI.create(path + "?captureId=" + URLEncoder.encode(request.captureId(), StandardCharsets.UTF_8));
        return new ProbeEndpointRequest(
                endpoint,
                "GET",
                Map.of(),
                "",
                endpointConfiguration.requestPolicy().timeoutOrDefault(request.timeout()),
                endpointConfiguration);
    }
}
