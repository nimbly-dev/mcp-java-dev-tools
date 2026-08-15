package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action;

import java.util.Arrays;
import java.util.Optional;

/**
 * Closed public action allowlist for the consolidated Route Synthesis Tool.
 */
public enum RouteSynthesisAction {

    INFER_TARGET("infer_target"),
    CLASS_METHODS("class_methods"),
    DISCOVER_HANDLERS("discover_handlers"),
    CREATE_RECIPE("create_recipe");

    private final String value;

    RouteSynthesisAction(String value) {
        this.value = value;
    }

    /**
     * Returns the stable MCP action value.
     *
     * @return action value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves an MCP action value without throwing.
     *
     * @param value requested action value
     * @return matching action when supported
     */
    public static Optional<RouteSynthesisAction> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst();
    }
}
