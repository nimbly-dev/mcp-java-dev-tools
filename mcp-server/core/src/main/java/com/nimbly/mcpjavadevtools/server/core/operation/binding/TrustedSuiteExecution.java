package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

/** Trusted CDE bridge from a Suite registration to workspace-owned plan and run state. */
public interface TrustedSuiteExecution {
    /** Resolves persisted inputs, invokes the typed owner, and persists mutating outcomes. */
    <T> T execute(String suiteType, JsonNode selector, Class<T> resultType,
            Function<JsonNode, T> owner, Function<String, T> blocked, boolean persist);
}
