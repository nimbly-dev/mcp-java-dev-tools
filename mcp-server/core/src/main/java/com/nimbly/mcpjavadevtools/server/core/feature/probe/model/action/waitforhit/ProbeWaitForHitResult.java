package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import java.util.Objects;

/**
 * Bounded evidence retained from a wait-for-hit operation.
 */
public record ProbeWaitForHitResult(
        String key,
        ProbeWaitOutcome outcome,
        int attempts,
        Long baselineHitCount,
        Long observedHitCount,
        Long observedLastHitEpoch,
        ProbeStatusEntry lastStatus) implements ProbeActionResult {

    /**
     * Validates mandatory identity and outcome values.
     */
    public ProbeWaitForHitResult {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }
}
