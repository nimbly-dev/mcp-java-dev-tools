package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitAttemptState;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitPollingSettings;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.time.Clock;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Performs bounded wait-for-hit polling after strict target validation.
 */
@RequiredArgsConstructor
public final class ProbeWaitPollingOperation {

    @NonNull private final ProbeStatusAction statusAction;
    @NonNull private final ProbeRequestPolicy requestPolicy;
    @NonNull private final Clock clock;
    @NonNull private final ProbeWaitSleeper sleeper;

    public ProbeResult poll(ProbeWaitPollingSettings settings, int retries) {
        long windowStart = clock.millis();
        ProbeStatusEntry lastStatus = null;
        ProbeWaitStatusReader reader = new ProbeWaitStatusReader(statusAction, requestPolicy, sleeper);
        for (int attempt = 1; attempt <= retries; attempt++) {
            ProbeResult baselineResult = reader.read(settings);
            ProbeResult failure = ProbeWaitResultFactory.failure(baselineResult, settings.key(), attempt, lastStatus);
            if (failure != null) {
                return failure;
            }
            Optional<ProbeStatusEntry> baseline = ProbeWaitStatusEvidence.entry(baselineResult);
            if (baseline.isEmpty()) {
                return ProbeWaitResultFactory.result(
                        ProbeReasonCode.PROBE_UNREACHABLE, settings.key(), ProbeWaitOutcome.UNREACHABLE, attempt, lastStatus, lastStatus);
            }
            ProbeResult outcome = ProbeWaitOutcomeEvaluator.baseline(settings.key(), attempt, windowStart, baseline.get());
            if (outcome != null) {
                return outcome;
            }
            ProbeResult attemptOutcome = pollAttempt(
                    settings,
                    new ProbeWaitAttemptState(attempt, windowStart, baseline.get(), attempt == retries),
                    reader);
            if (attemptOutcome != null) {
                return attemptOutcome;
            }
            lastStatus = baseline.get();
        }
        return ProbeWaitResultFactory.timeout(settings.key(), retries, lastStatus, false);
    }

    private ProbeResult pollAttempt(
            ProbeWaitPollingSettings settings,
            ProbeWaitAttemptState state,
            ProbeWaitStatusReader reader) {
        long deadline = clock.millis() + settings.timeout().toMillis();
        ProbeStatusEntry lastStatus = state.baseline();
        boolean staleEvidence = false;
        while (clock.millis() < deadline) {
            ProbeResult polledResult = reader.read(settings);
            ProbeResult failure = ProbeWaitResultFactory.failure(polledResult, settings.key(), state.attempt(), lastStatus);
            if (failure != null) {
                return failure;
            }
            Optional<ProbeStatusEntry> current = ProbeWaitStatusEvidence.entry(polledResult);
            if (current.isEmpty()) {
                return ProbeWaitResultFactory.result(
                        ProbeReasonCode.PROBE_UNREACHABLE,
                        settings.key(),
                        ProbeWaitOutcome.UNREACHABLE,
                        state.attempt(),
                        lastStatus,
                        lastStatus);
            }
            lastStatus = current.get();
            ProbeResult outcome = ProbeWaitOutcomeEvaluator.polled(settings.key(), state, lastStatus);
            if (outcome != null) {
                return outcome;
            }
            staleEvidence = staleEvidence || ProbeWaitStatusEvidence.countIncreased(state.baseline(), lastStatus);
            try {
                sleeper.sleep(settings.interval());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ProbeWaitResultFactory.result(
                        ProbeReasonCode.WAIT_INTERRUPTED,
                        settings.key(),
                        ProbeWaitOutcome.INTERRUPTED,
                        state.attempt(),
                        state.baseline(),
                        lastStatus);
            }
        }
        return state.finalAttempt() ? ProbeWaitResultFactory.timeout(settings.key(), state.attempt(), lastStatus, staleEvidence) : null;
    }
}
