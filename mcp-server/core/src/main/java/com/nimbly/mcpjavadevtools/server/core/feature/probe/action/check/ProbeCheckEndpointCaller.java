package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls one existing Sidecar diagnostic endpoint using the bounded endpoint contract.
 */
public final class ProbeCheckEndpointCaller {

    private static final String DIAGNOSTIC_KEY = "mcp.jvm.diagnose#key";
    private static final String DIAGNOSTIC_RESET_PAYLOAD = "{\"key\":\"mcp.jvm.diagnose#key\"}";

    private ProbeCheckEndpointCaller() {
    }

    public static ProbeCheckEndpointResult call(
            ProbeTarget target,
            ProbeCheckRequest request,
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeEndpointClient endpointClient,
            ProbeResponseCompactionPolicy compactionPolicy,
            boolean reset) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (reset) {
            headers.put("content-type", "application/json");
        }
        headers.putAll(request.headers());
        URI endpoint = target.baseUrl().resolve(reset
                ? endpointConfiguration.paths().resetPath()
                : endpointConfiguration.paths().statusPath());
        if (!reset) {
            endpoint = URI.create(endpoint + "?key=" + URLEncoder.encode(DIAGNOSTIC_KEY, StandardCharsets.UTF_8));
        }
        ProbeEndpointRequest endpointRequest = new ProbeEndpointRequest(
                endpoint,
                reset ? "POST" : "GET",
                headers,
                reset ? DIAGNOSTIC_RESET_PAYLOAD : "",
                endpointConfiguration.requestPolicy().timeoutOrDefault(request.timeout()),
                endpointConfiguration);
        try {
            return ProbeCheckEndpointResponseMapper.map(
                    endpointClient.exchange(endpointRequest),
                    !reset,
                    compactionPolicy);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() == ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            return ProbeCheckEndpointResponseMapper.map(null, !reset, compactionPolicy);
        }
    }
}
