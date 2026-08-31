package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation;

/** Canonical expected failure fingerprint fields supplied by the caller. */
public record FailureExpectedFingerprintArguments(
        String exceptionType,
        String rootCauseType,
        String nearestApplicationMethodKey) {
}
