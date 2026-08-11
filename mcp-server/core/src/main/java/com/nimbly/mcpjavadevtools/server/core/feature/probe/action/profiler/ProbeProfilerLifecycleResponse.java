package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.Optional;

/**
 * Converts Sidecar profiler lifecycle responses into typed Core results.
 */
class ProbeProfilerLifecycleResponse {

    private ProbeProfilerLifecycleResponse() {
    }

    static ProbeResult toResult(
            ProbeProfilerRequest request,
            ProbeEndpointResponse response,
            ProbeResponseCompactionPolicy compactionPolicy) {
        Optional<JsonNode> root = ProbeProfilerPayloadParser.object(response.payload());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || root.isEmpty()) {
            String reason = root.map(value -> value.path("error"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .orElse("profiler_failed");
            return ProbeProfilerFailure.result(request, reason, compactionPolicy);
        }
        Optional<ProbeProfilerResult> result = ProbeProfilerPayloadResult.from(root.get(), request, compactionPolicy);
        return result.map(ProbeResult::success)
                .orElseGet(() -> ProbeProfilerFailure.result(request, "malformed_response", compactionPolicy));
    }
}
