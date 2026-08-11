package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;

/**
 * Produces deterministic action validation and failure results.
 */
class ProbeActuateFailure {

    private ProbeActuateFailure() {
    }

    static ProbeResult result(
            ProbeReasonCode reasonCode,
            ProbeActuateRequest request,
            String reason,
            ProbeResponseCompactionPolicy compactionPolicy) {
        return ProbeResult.withActionResult(
                reasonCode,
                ProbeReasonMetadata.inputValidation(),
                new ProbeActuateResult(
                        false,
                        request.command() == null ? null : request.command().value(),
                        null,
                        request.sessionId(),
                        request.actuatorId(),
                        request.targetKey(),
                        request.returnBoolean(),
                        request.ttlMs(),
                        null,
                        null,
                        ProbeResponseTextCompactor.compact(reason, compactionPolicy)));
    }
}
