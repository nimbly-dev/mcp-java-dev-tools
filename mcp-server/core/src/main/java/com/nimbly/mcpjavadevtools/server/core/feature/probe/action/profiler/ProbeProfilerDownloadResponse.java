package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerDownload;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;
import java.io.IOException;
import java.util.Optional;

/**
 * Writes a successful bounded profiler download through the configured output port.
 */
class ProbeProfilerDownloadResponse {

    private ProbeProfilerDownloadResponse() {
    }

    static ProbeResult toResult(
            ProbeProfilerRequest request,
            ProbeEndpointResponse response,
            ProbeResponseCompactionPolicy compactionPolicy,
            ProbeProfilerOutputStore outputStore) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Optional<JsonNode> root = ProbeProfilerPayloadParser.object(response.payload());
            String reason = root.map(value -> value.path("error"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .orElse("profiler_failed");
            return ProbeProfilerFailure.result(request, reason, compactionPolicy);
        }
        try {
            ProbeProfilerDownload download = outputStore.write(request.outputPath(), response.payloadBytes());
            return ProbeResult.success(new ProbeProfilerResult(
                    request.command().value(),
                    "downloaded",
                    true,
                    null,
                    request.sessionId(),
                    null,
                    null,
                    null,
                    null,
                    ProbeResponseTextCompactor.compact(download.outputPath(), compactionPolicy),
                    request.outputFormat(),
                    null,
                    download.bytesWritten()));
        } catch (IOException exception) {
            return ProbeProfilerFailure.result(request, "output_write_failed", compactionPolicy);
        }
    }
}
