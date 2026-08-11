package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed batch reset input for explicit Strict Line Keys.
 */
public record ProbeBatchResetRequest(
        ProbeTargetSelector targetSelector,
        ProbeKeyBatchSelector keySelector,
        Duration timeout) implements ProbeResetRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.RESET;
    }
}
