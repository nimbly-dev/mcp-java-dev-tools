package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation;

/** Canonical bounded investigation controls shared by Failure Analysis operations. */
public record FailureInvestigationArguments(String mode, int attemptLimit, long elapsedTimeLimitMs) {
}
