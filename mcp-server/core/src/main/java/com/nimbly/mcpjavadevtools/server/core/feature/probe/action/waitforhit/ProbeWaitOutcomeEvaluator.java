package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitAttemptState;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Derives terminal wait outcomes from validated baseline and polling evidence.
 */
class ProbeWaitOutcomeEvaluator {

    private ProbeWaitOutcomeEvaluator() {
    }

    static ProbeResult baseline(StrictLineKey key, int attempt, long windowStart, ProbeStatusEntry baseline) {
        if (ProbeWaitStatusEvidence.invalidLineTarget(baseline)) {
            return ProbeWaitResultFactory.result(
                    ProbeReasonCode.INVALID_LINE_TARGET, key, ProbeWaitOutcome.INVALID_LINE_TARGET, attempt, baseline, baseline);
        }
        if (ProbeWaitStatusEvidence.baselineHit(baseline, windowStart)) {
            return ProbeWaitResultFactory.result(
                    ProbeReasonCode.SUCCESS, key, ProbeWaitOutcome.LINE_HIT, attempt, baseline, baseline);
        }
        return null;
    }

    static ProbeResult polled(StrictLineKey key, ProbeWaitAttemptState state, ProbeStatusEntry current) {
        if (ProbeWaitStatusEvidence.invalidLineTarget(current)) {
            return ProbeWaitResultFactory.result(
                    ProbeReasonCode.INVALID_LINE_TARGET,
                    key,
                    ProbeWaitOutcome.INVALID_LINE_TARGET,
                    state.attempt(),
                    state.baseline(),
                    current);
        }
        if (ProbeWaitStatusEvidence.inlineHit(current, state.baseline().hitCount(), state.windowStart())) {
            return ProbeWaitResultFactory.result(
                    ProbeReasonCode.SUCCESS, key, ProbeWaitOutcome.LINE_HIT, state.attempt(), state.baseline(), current);
        }
        return null;
    }
}
