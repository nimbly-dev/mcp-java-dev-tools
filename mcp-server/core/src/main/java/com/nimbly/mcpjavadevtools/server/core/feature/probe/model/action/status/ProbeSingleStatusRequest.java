package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed single-key status input with optional method-key line-hint resolution.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param keySelector strict key or method key plus optional line hint
 * @param timeout optional per-request timeout
 */
public record ProbeSingleStatusRequest(
        ProbeTargetSelector targetSelector,
        ProbeKeySelector keySelector,
        Duration timeout) implements ProbeStatusRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.STATUS;
    }
}
