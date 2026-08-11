package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import java.time.Duration;

/**
 * Immutable, bounded timing and target values for one wait-for-hit operation.
 *
 * @param request typed wait request
 * @param key normalized Strict Line Key
 * @param timeout bounded polling window
 * @param interval bounded status polling interval
 */
public record ProbeWaitPollingSettings(
        ProbeWaitForHitRequest request,
        StrictLineKey key,
        Duration timeout,
        Duration interval) {
}
