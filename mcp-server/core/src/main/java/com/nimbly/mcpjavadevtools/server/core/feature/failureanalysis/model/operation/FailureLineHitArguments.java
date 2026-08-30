package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation;

/** Canonical Strict Line Key evidence supplied to reproduction verification. */
public record FailureLineHitArguments(String strictLineKey, int hitCount) {
}
