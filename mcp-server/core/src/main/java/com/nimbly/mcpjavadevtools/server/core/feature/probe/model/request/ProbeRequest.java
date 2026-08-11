package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;

/**
 * Common contract implemented by action-specific Probe requests.
 *
 * <p>Individual action stories own their additional typed fields rather than
 * introducing a generic payload map into the Core boundary.</p>
 */
public interface ProbeRequest {

    /**
     * Returns the requested public Probe action.
     *
     * @return requested action
     */
    ProbeAction action();

    /**
     * Returns the shared Probe target selector.
     *
     * @return shared target selector
     */
    ProbeTargetSelector targetSelector();
}
