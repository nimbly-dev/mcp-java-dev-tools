package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;

/** Classifies the externally visible effect of a Probe action. */
final class ProbeOperationEffects {

    private ProbeOperationEffects() {
    }

    static String sideEffect(ProbeAction action) {
        return switch (action) {
            case CHECK, STATUS -> "probe_endpoint_read";
            case RESET, ACTUATE -> "probe_endpoint_write";
            case WAIT_FOR_HIT -> "probe_endpoint_poll";
            case CAPTURE -> "probe_capture_read";
            case PROFILER -> "probe_artifact_write";
        };
    }
}
