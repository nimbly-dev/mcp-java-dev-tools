package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Closed MCP action discriminator matching the TypeScript compatibility schema. */
public enum RouteSynthesisMcpAction {

    infer_target("infer_target"),
    class_methods("class_methods"),
    discover_handlers("discover_handlers"),
    create_recipe("create_recipe");

    private final String value;

    RouteSynthesisMcpAction(String value) {
        this.value = value;
    }

    /** Returns the stable wire value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Resolves the stable wire value during Jackson binding. */
    @JsonCreator
    public static RouteSynthesisMcpAction fromValue(String value) {
        for (RouteSynthesisMcpAction action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unsupported Route Synthesis action");
    }
}
