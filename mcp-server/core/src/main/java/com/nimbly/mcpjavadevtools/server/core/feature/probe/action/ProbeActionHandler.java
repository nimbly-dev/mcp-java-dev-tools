package com.nimbly.mcpjavadevtools.server.core.feature.probe.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Probe-specific specialization of the capability-neutral action handler contract.
 */
public interface ProbeActionHandler extends ActionHandler<ProbeAction, ProbeRequest, ProbeResult> {
}
