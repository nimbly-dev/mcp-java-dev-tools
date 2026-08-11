package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.Optional;

/**
 * Interprets only deterministic status evidence used by wait-for-hit polling.
 */
class ProbeWaitStatusEvidence {

    private ProbeWaitStatusEvidence() {
    }

    static Optional<ProbeStatusEntry> entry(ProbeResult result) {
        if (result.actionResult().isEmpty() || !(result.actionResult().get() instanceof ProbeStatusResult status)) {
            return Optional.empty();
        }
        if (status.entries().size() != 1 || !Boolean.TRUE.equals(status.entries().get(0).endpointOk())) {
            return Optional.empty();
        }
        return Optional.of(status.entries().get(0));
    }

    static boolean targetResolutionFailure(ProbeResult result) {
        return result.reasonCode() == ProbeReasonCode.PROBE_ID_REQUIRED
                || result.reasonCode() == ProbeReasonCode.PROBE_ID_UNKNOWN
                || result.reasonCode() == ProbeReasonCode.INVALID_PROBE_TARGET;
    }

    static boolean invalidLineTarget(ProbeStatusEntry entry) {
        return Boolean.FALSE.equals(entry.lineResolvable()) || "invalid_line_target".equals(entry.lineValidation());
    }

    static boolean baselineHit(ProbeStatusEntry baseline, long windowStart) {
        return baseline.hitCount() != null
                && baseline.hitCount() > 0
                && baseline.lastHitEpoch() != null
                && baseline.lastHitEpoch() >= windowStart;
    }

    static boolean countIncreased(ProbeStatusEntry baseline, ProbeStatusEntry current) {
        return baseline.hitCount() != null && current.hitCount() != null && current.hitCount() > baseline.hitCount();
    }

    static boolean inlineHit(ProbeStatusEntry current, Long baselineHitCount, long windowStart) {
        return baselineHitCount != null
                && current.hitCount() != null
                && current.hitCount() > baselineHitCount
                && current.lastHitEpoch() != null
                && current.lastHitEpoch() >= windowStart;
    }
}
