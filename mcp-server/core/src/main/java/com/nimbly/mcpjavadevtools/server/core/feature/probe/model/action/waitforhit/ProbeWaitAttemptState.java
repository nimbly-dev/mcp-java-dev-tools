package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;

/**
 * Immutable baseline and retry state for one bounded wait-for-hit attempt.
 *
 * @param attempt one-based bounded retry attempt
 * @param windowStart start epoch for accepting only new evidence
 * @param baseline status evidence before polling begins
 * @param finalAttempt whether a timeout becomes terminal
 */
public record ProbeWaitAttemptState(
        int attempt,
        long windowStart,
        ProbeStatusEntry baseline,
        boolean finalAttempt) {
}
