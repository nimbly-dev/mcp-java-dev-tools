package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitPollingSettings;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reads a single status with the configured unreachable retry policy.
 */
@RequiredArgsConstructor
class ProbeWaitStatusReader {

    @NonNull private final ProbeStatusAction statusAction;
    @NonNull private final ProbeRequestPolicy requestPolicy;
    @NonNull private final ProbeWaitSleeper sleeper;

    ProbeResult read(ProbeWaitPollingSettings settings) {
        int maximumAttempts = requestPolicy.unreachableRetryEnabled() ? requestPolicy.unreachableMaxRetries() : 1;
        ProbeResult result = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            result = statusAction.execute(new ProbeSingleStatusRequest(
                    settings.request().targetSelector(),
                    new ProbeKeySelector(settings.key().value(), null),
                    settings.timeout()));
            if (result.reasonCode() != ProbeReasonCode.PROBE_UNREACHABLE || attempt == maximumAttempts) {
                return result;
            }
            try {
                sleeper.sleep(settings.interval());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ProbeResult.blocked(ProbeReasonCode.WAIT_INTERRUPTED, ProbeReasonMetadata.status());
            }
        }
        return result;
    }
}
