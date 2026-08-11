package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action;

import java.util.Arrays;
import java.util.Optional;

/**
 * Closed allowlist for the public consolidated Probe actions.
 */
public enum ProbeAction {

    CHECK("check"),
    STATUS("status"),
    RESET("reset"),
    WAIT_FOR_HIT("wait_for_hit"),
    CAPTURE("capture"),
    ACTUATE("actuate"),
    PROFILER("profiler");

    private final String value;

    ProbeAction(String value) {
        this.value = value;
    }

    /**
     * Returns the stable action value used by the MCP contract.
     *
     * @return stable action value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a stable action value without throwing for invalid input.
     *
     * @param value action value from a boundary request
     * @return matching action when supported
     */
    public static Optional<ProbeAction> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst();
    }
}
