package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Closed create_recipe intent values matching the TypeScript contract. */
public enum RouteSynthesisMcpIntentMode {

    LINE_PROBE("line_probe"),
    REGRESSION("regression");

    private final String value;

    RouteSynthesisMcpIntentMode(String value) {
        this.value = value;
    }

    /** Returns the stable wire value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Resolves the stable wire value during Jackson binding. */
    @JsonCreator
    public static RouteSynthesisMcpIntentMode fromValue(String value) {
        for (RouteSynthesisMcpIntentMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported Route Synthesis intent mode");
    }
}
