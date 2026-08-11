package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;

/**
 * Creates deterministic failed profiler results without exposing endpoint detail.
 */
public final class ProbeProfilerFailure {

    private ProbeProfilerFailure() {
    }

    public static ProbeResult result(
            ProbeProfilerRequest request,
            String reason,
            ProbeResponseCompactionPolicy compactionPolicy) {
        return ProbeResult.withActionResult(
                ProbeReasonCode.PROFILER_FAILED,
                ProbeReasonMetadata.status(),
                new ProbeProfilerResult(
                        request.command() == null ? null : request.command().value(),
                        "failed",
                        false,
                        null,
                        request.sessionId(),
                        null,
                        null,
                        null,
                        null,
                        request.outputPath() == null
                                ? null
                                : ProbeResponseTextCompactor.compact(request.outputPath(), compactionPolicy),
                        request.outputFormat(),
                        ProbeResponseTextCompactor.compact(reason, compactionPolicy),
                        null));
    }
}
