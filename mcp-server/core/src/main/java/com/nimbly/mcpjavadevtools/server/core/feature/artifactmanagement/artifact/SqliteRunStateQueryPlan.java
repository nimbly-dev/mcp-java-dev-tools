package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.util.List;

/** Immutable SQL and bind values produced for one run-state query. */
record SqliteRunStateQueryPlan(String selectSql, String countSql, List<Object> parameters) {
}
