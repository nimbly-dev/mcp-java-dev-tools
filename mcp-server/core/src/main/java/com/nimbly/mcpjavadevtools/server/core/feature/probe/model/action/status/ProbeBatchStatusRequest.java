package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed batch status input for explicit Strict Line Keys.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param keySelector batch Strict Line Key selector
 * @param timeout optional per-request timeout
 */
public record ProbeBatchStatusRequest(
        ProbeTargetSelector targetSelector,
        ProbeKeyBatchSelector keySelector,
        Duration timeout) implements ProbeStatusRequest {

    @Override
    public ProbeAction action() {
        return ProbeAction.STATUS;
    }
}
