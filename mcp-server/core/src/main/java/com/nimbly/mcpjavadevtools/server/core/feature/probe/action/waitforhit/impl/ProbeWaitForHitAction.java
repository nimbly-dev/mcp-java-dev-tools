package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.ProbeWaitPollingOperation;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.ProbeWaitSleeper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitPollingSettings;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Confirms only bounded, post-baseline Strict Line Key evidence.
 */
@RequiredArgsConstructor
public final class ProbeWaitForHitAction implements ProbeActionHandler {

    @NonNull private final ProbeStatusAction statusAction;
    @NonNull private final ProbeRequestPolicy requestPolicy;
    @NonNull private final Clock clock;
    @NonNull private final ProbeWaitSleeper sleeper;

    /**
     * Dispatches only the wait-for-hit request type owned by this action.
     *
     * @param request typed consolidated Probe request
     * @return deterministic wait outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeWaitForHitRequest waitRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return waitForHit(waitRequest);
        } catch (IllegalArgumentException exception) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
    }

    /**
     * Returns the public Probe action implemented here.
     *
     * @return wait-for-hit action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.WAIT_FOR_HIT;
    }

    private ProbeResult waitForHit(ProbeWaitForHitRequest request) {
        if (request.keySelector() == null) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        Optional<StrictLineKey> strictKey = request.keySelector().resolve();
        if (strictKey.isEmpty()) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        Duration timeout = requestPolicy.timeoutOrDefault(request.timeout());
        Duration interval = request.pollInterval() == null
                ? requestPolicy.defaultPollInterval()
                : requestPolicy.bounds().clampPollInterval(request.pollInterval());
        int retries = request.maxRetries() == null
                ? requestPolicy.maxRetries()
                : requestPolicy.bounds().clampRetries(request.maxRetries());
        ProbeWaitPollingSettings settings = new ProbeWaitPollingSettings(request, strictKey.get(), timeout, interval);
        return new ProbeWaitPollingOperation(statusAction, requestPolicy, clock, sleeper).poll(settings, retries);
    }
}
