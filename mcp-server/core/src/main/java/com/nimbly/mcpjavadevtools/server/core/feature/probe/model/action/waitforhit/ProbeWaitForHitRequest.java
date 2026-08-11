package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed bounded wait request for one Strict Line Key.
 */
public record ProbeWaitForHitRequest(
        ProbeTargetSelector targetSelector,
        ProbeKeySelector keySelector,
        Duration timeout,
        Duration pollInterval,
        Integer maxRetries) implements ProbeRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.WAIT_FOR_HIT;
    }
}
