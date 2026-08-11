package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler;

/**
 * Closed lifecycle command allowlist for the Sidecar profiler.
 */
public enum ProbeProfilerCommand {

    START("start"),
    STOP("stop"),
    RESET("reset"),
    STATUS("status"),
    DOWNLOAD("download");

    private final String value;

    ProbeProfilerCommand(String value) {
        this.value = value;
    }

    /**
     * Returns the Sidecar lifecycle action value.
     *
     * @return stable action value
     */
    public String value() {
        return value;
    }
}
