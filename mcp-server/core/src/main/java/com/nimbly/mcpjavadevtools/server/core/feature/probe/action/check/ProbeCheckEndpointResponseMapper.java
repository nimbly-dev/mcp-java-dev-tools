package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeStatusPayload;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeHeaderRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseJsonParser;
import java.util.Map;
import java.util.Optional;

/**
 * Normalizes bounded Sidecar diagnostic endpoint responses into check results.
 */
class ProbeCheckEndpointResponseMapper {

    private ProbeCheckEndpointResponseMapper() {
    }

    static ProbeCheckEndpointResult map(
            ProbeEndpointResponse response,
            boolean statusResponse,
            ProbeResponseCompactionPolicy compactionPolicy) {
        if (response == null) {
            return new ProbeCheckEndpointResult(ProbeCheckEndpointStatus.UNREACHABLE, null, null, Map.of(), null);
        }
        ProbeCheckEndpointStatus status = ProbeCheckEndpointStatus.UNAVAILABLE;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            status = ProbeCheckEndpointStatus.UNAUTHORIZED;
        } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
            status = ProbeCheckEndpointStatus.AVAILABLE;
        }
        Optional<ProbeStatusPayload> payload = statusResponse
                ? ProbeResponseJsonParser.parseStatus(response.payload(), compactionPolicy)
                : Optional.empty();
        if (statusResponse && status == ProbeCheckEndpointStatus.AVAILABLE && payload.isEmpty()) {
            status = ProbeCheckEndpointStatus.MALFORMED_RESPONSE;
        }
        return new ProbeCheckEndpointResult(
                status,
                response.statusCode(),
                payload.map(ProbeStatusPayload::key).orElse(null),
                ProbeHeaderRedactor.redact(response.headers(), compactionPolicy),
                payload.map(ProbeStatusPayload::runtime).orElse(null));
    }
}
