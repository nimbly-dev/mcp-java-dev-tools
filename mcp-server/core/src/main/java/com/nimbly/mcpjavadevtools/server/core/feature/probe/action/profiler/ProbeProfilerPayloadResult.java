package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;
import java.util.Optional;

/**
 * Maps a validated profiler envelope to a compact immutable action result.
 */
class ProbeProfilerPayloadResult {

    private ProbeProfilerPayloadResult() {
    }

    static Optional<ProbeProfilerResult> from(
            JsonNode root,
            ProbeProfilerRequest request,
            ProbeResponseCompactionPolicy compactionPolicy) {
        if (!root.path("ok").isBoolean() || !root.path("ok").booleanValue() || !root.path("profiler").isObject()) {
            return Optional.empty();
        }
        JsonNode profiler = root.path("profiler");
        JsonNode status = profiler.path("status");
        if (!status.isTextual()) {
            return Optional.empty();
        }
        return Optional.of(new ProbeProfilerResult(
                request.command().value(),
                ProbeResponseTextCompactor.compact(status.asText(), compactionPolicy),
                profiler.path("supported").isBoolean() ? profiler.path("supported").booleanValue() : null,
                profiler.path("provider").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("provider").asText(), compactionPolicy) : null,
                profiler.path("sessionId").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("sessionId").asText(), compactionPolicy) : null,
                profiler.path("startedAtEpochMs").canConvertToLong() ? profiler.path("startedAtEpochMs").longValue() : null,
                profiler.path("stoppedAtEpochMs").canConvertToLong() ? profiler.path("stoppedAtEpochMs").longValue() : null,
                profiler.path("event").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("event").asText(), compactionPolicy) : null,
                profiler.path("intervalNanos").canConvertToLong() ? profiler.path("intervalNanos").longValue() : null,
                profiler.path("outputPath").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("outputPath").asText(), compactionPolicy) : null,
                profiler.path("outputFormat").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("outputFormat").asText(), compactionPolicy) : null,
                profiler.path("detail").isTextual()
                        ? ProbeResponseTextCompactor.compact(profiler.path("detail").asText(), compactionPolicy) : null,
                null));
    }
}
