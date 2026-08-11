package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Selects the lifecycle or download response normalization path.
 */
class ProbeProfilerResponseMapper {

    private ProbeProfilerResponseMapper() {
    }

    static ProbeResult toResult(
            ProbeProfilerRequest request,
            ProbeEndpointResponse response,
            ProbeResponseCompactionPolicy compactionPolicy,
            ProbeProfilerOutputStore outputStore) {
        if (request.command() == ProbeProfilerCommand.DOWNLOAD) {
            return ProbeProfilerDownloadResponse.toResult(request, response, compactionPolicy, outputStore);
        }
        return ProbeProfilerLifecycleResponse.toResult(request, response, compactionPolicy);
    }
}
