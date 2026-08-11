package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed input for one bounded Probe capture lookup.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param captureId required Sidecar capture identifier
 * @param timeout optional bounded endpoint timeout
 */
public record ProbeCaptureRequest(
        ProbeTargetSelector targetSelector,
        String captureId,
        Duration timeout) implements ProbeRequest {

    /**
     * Normalizes the optional identifier without accepting a blank value.
     */
    public ProbeCaptureRequest {
        captureId = captureId == null ? null : captureId.trim();
    }

    @Override
    public ProbeAction action() {
        return ProbeAction.CAPTURE;
    }
}
