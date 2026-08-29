package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Mutable SQL predicate context for one validated run-state query. */
record SqliteRunStateQueryContext(
        String table,
        JsonNode query,
        JsonNode filters,
        List<String> predicates,
        List<Object> parameters) {
}
