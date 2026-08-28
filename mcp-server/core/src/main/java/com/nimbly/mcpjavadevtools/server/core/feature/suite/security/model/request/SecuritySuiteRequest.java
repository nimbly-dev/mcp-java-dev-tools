package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;

/** Immutable request envelope for one Security Suite action. */
public record SecuritySuiteRequest(SecuritySuiteAction action, JsonNode input) {

    /** Normalizes omitted input to JSON null. */
    public SecuritySuiteRequest {
        input = input == null ? NullNode.getInstance() : input;
    }
}
