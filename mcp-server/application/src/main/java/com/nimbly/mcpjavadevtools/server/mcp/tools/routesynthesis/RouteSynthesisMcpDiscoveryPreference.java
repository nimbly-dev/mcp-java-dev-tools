package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Closed runtime mapping preference matching the TypeScript contract. */
public enum RouteSynthesisMcpDiscoveryPreference {

    STATIC_ONLY("static_only"),
    RUNTIME_FIRST("runtime_first"),
    RUNTIME_ONLY("runtime_only");

    private final String value;

    RouteSynthesisMcpDiscoveryPreference(String value) {
        this.value = value;
    }

    /** Returns the stable wire value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Resolves the stable wire value during Jackson binding. */
    @JsonCreator
    public static RouteSynthesisMcpDiscoveryPreference fromValue(String value) {
        for (RouteSynthesisMcpDiscoveryPreference preference : values()) {
            if (preference.value.equals(value)) {
                return preference;
            }
        }
        throw new IllegalArgumentException("Unsupported Route Synthesis discovery preference");
    }
}
