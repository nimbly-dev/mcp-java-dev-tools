package com.nimbly.mcpjavadevtools.server.mcp.tools.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic response envelope shared by action-based MCP Tools.
 *
 * @param resultType deterministic output shape discriminator
 * @param status deterministic output status
 * @param reasonCode stable reason code
 * @param nextActionCode stable next action for non-success outcomes
 * @param nextAction human-readable next action when one is prescribed
 * @param reason optional sanitized boundary reason
 * @param reasonMeta bounded reason metadata using the public field name
 * @param result typed action result when available
 * @param details additive action-specific deterministic output fields
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpActionResponse(
        String resultType,
        String status,
        String reasonCode,
        @Nullable String nextActionCode,
        @Nullable String nextAction,
        String reason,
        Map<String, Object> reasonMeta,
        @Nullable Object result,
        Map<String, Object> details) {

    /**
     * Normalizes response collections and keeps the compatibility output additive.
     */
    public McpActionResponse {
        resultType = resultType == null ? "report" : resultType;
        reasonMeta = copyMap(reasonMeta);
        details = copyMap(details);
    }

    /**
     * Flattens the TypeScript-compatible action details into the public output.
     *
     * @return additional deterministic output fields
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * Adds the compatibility fields alongside the explicit details envelope.
     *
     * @return flattened deterministic output fields
     */
    @JsonAnyGetter
    public Map<String, Object> flattenedDetails() {
        return details;
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
