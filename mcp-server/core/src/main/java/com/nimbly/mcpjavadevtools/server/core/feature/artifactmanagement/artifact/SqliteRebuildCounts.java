package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

/** Counts produced by one bounded run-state projection rebuild. */
record SqliteRebuildCounts(int scanned, int rebuilt, int invalid) {
}
