package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

/** Plan/run identity recovered from a legacy correlation Artifact path. */
record SqliteLegacyCorrelation(String planName, String runId) {
}
