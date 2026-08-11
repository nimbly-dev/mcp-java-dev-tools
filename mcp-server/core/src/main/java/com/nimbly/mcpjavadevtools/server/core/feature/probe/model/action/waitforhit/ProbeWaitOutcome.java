package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit;

/**
 * Closed evidence classification for a bounded Probe wait.
 */
public enum ProbeWaitOutcome {

    LINE_HIT,
    TIMEOUT,
    STALE_EVIDENCE,
    UNREACHABLE,
    INVALID_LINE_TARGET,
    INTERRUPTED
}
