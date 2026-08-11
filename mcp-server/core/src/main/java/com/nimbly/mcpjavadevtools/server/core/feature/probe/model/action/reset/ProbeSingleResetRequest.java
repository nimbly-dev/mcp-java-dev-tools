package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed single-key reset input with optional method-key line-hint resolution.
 */
public record ProbeSingleResetRequest(
        ProbeTargetSelector targetSelector,
        ProbeKeySelector keySelector,
        Duration timeout) implements ProbeResetRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.RESET;
    }
}
