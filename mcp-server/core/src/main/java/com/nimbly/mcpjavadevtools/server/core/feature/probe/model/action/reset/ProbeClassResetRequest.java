package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed fully-qualified class-scoped reset input.
 */
public record ProbeClassResetRequest(
        ProbeTargetSelector targetSelector,
        String className,
        Duration timeout) implements ProbeResetRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.RESET;
    }
}
