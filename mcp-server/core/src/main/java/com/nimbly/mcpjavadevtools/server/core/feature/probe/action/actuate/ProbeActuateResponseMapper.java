package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.Optional;

/**
 * Maps one bounded Sidecar actuation response to a typed Core outcome.
 */
class ProbeActuateResponseMapper {

    private ProbeActuateResponseMapper() {
    }

    static ProbeResult toResult(
            ProbeActuateRequest request,
            ProbeEndpointResponse response,
            ProbeResponseCompactionPolicy compactionPolicy) {
        Optional<JsonNode> root = ProbeActuatePayloadParser.object(response.payload());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || root.isEmpty()) {
            String reason = root.map(value -> value.path("error"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .orElse("actuation_failed");
            return ProbeActuateFailure.result(ProbeReasonCode.ACTUATION_FAILED, request, reason, compactionPolicy);
        }
        Optional<ProbeActuateResult> result = ProbeActuatePayloadResult.from(root.get(), request, compactionPolicy);
        return result.map(ProbeResult::success).orElseGet(
                () -> ProbeActuateFailure.result(
                        ProbeReasonCode.ACTUATION_FAILED, request, "malformed_response", compactionPolicy));
    }
}
