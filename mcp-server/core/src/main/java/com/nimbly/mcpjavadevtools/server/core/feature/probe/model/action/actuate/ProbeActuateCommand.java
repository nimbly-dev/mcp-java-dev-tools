package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate;

/**
 * Closed Sidecar actuation command allowlist.
 */
public enum ProbeActuateCommand {

    ARM("arm"),
    DISARM("disarm");

    private final String value;

    ProbeActuateCommand(String value) {
        this.value = value;
    }

    /**
     * Returns the Sidecar action value.
     *
     * @return stable action value
     */
    public String value() {
        return value;
    }
}
