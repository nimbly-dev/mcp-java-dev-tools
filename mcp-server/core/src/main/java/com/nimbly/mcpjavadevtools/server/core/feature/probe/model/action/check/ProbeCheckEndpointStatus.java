package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check;

/**
 * Closed safe classification for one endpoint exercised by Probe check.
 */
public enum ProbeCheckEndpointStatus {

    AVAILABLE,
    UNAUTHORIZED,
    UNAVAILABLE,
    UNREACHABLE,
    MALFORMED_RESPONSE
}
