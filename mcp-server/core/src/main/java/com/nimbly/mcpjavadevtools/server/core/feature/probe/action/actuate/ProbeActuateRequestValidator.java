package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Enforces command-specific session and Strict Line Key actuation invariants.
 */
public final class ProbeActuateRequestValidator {

    private ProbeActuateRequestValidator() {
    }

    public static ProbeResult validate(ProbeActuateRequest request, ProbeResponseCompactionPolicy compactionPolicy) {
        if (request.command() == null || request.sessionId() == null) {
            return ProbeActuateFailure.result(ProbeReasonCode.SESSION_ID_REQUIRED, request, "session_id_required", compactionPolicy);
        }
        if (request.command() == ProbeActuateCommand.ARM) {
            if (request.targetKey() == null || StrictLineKey.parse(request.targetKey()).isEmpty()) {
                return ProbeActuateFailure.result(ProbeReasonCode.LINE_KEY_REQUIRED, request, "line_key_required", compactionPolicy);
            }
            if (request.returnBoolean() == null) {
                return ProbeActuateFailure.result(
                        ProbeReasonCode.RETURN_BOOLEAN_REQUIRED, request, "return_boolean_required", compactionPolicy);
            }
            if (request.ttlMs() == null || request.ttlMs() <= 0) {
                return ProbeActuateFailure.result(ProbeReasonCode.TTL_REQUIRED, request, "ttl_required", compactionPolicy);
            }
            return null;
        }
        if (request.targetKey() != null || request.returnBoolean() != null || request.ttlMs() != null) {
            return ProbeActuateFailure.result(
                    ProbeReasonCode.DISARM_FIELDS_NOT_ALLOWED, request, "disarm_fields_not_allowed", compactionPolicy);
        }
        return null;
    }
}
