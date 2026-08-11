package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Creates deterministic, compact wait-for-hit Core outcomes.
 */
class ProbeWaitResultFactory {

    private ProbeWaitResultFactory() {
    }

    static ProbeResult failure(ProbeResult result, StrictLineKey key, int attempt, ProbeStatusEntry lastStatus) {
        if (result.reasonCode() == ProbeReasonCode.WAIT_INTERRUPTED) {
            return result(
                    ProbeReasonCode.WAIT_INTERRUPTED,
                    key,
                    ProbeWaitOutcome.INTERRUPTED,
                    attempt,
                    lastStatus,
                    lastStatus);
        }
        return ProbeWaitStatusEvidence.targetResolutionFailure(result) ? result : null;
    }

    static ProbeResult timeout(StrictLineKey key, int attempt, ProbeStatusEntry lastStatus, boolean staleEvidence) {
        if (staleEvidence) {
            return result(
                    ProbeReasonCode.STALE_PROBE_EVIDENCE,
                    key,
                    ProbeWaitOutcome.STALE_EVIDENCE,
                    attempt,
                    lastStatus,
                    lastStatus);
        }
        return result(ProbeReasonCode.WAIT_TIMEOUT, key, ProbeWaitOutcome.TIMEOUT, attempt, lastStatus, lastStatus);
    }

    static ProbeResult result(
            ProbeReasonCode reasonCode,
            StrictLineKey key,
            ProbeWaitOutcome waitOutcome,
            int attempt,
            ProbeStatusEntry baseline,
            ProbeStatusEntry observed) {
        ProbeWaitForHitResult result = new ProbeWaitForHitResult(
                key.value(),
                waitOutcome,
                attempt,
                baseline == null ? null : baseline.hitCount(),
                observed == null ? null : observed.hitCount(),
                observed == null ? null : observed.lastHitEpoch(),
                observed);
        if (reasonCode == ProbeReasonCode.SUCCESS) {
            return ProbeResult.success(result);
        }
        return ProbeResult.withActionResult(reasonCode, ProbeReasonMetadata.status(), result);
    }
}
