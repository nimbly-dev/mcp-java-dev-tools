package com.nimbly.mcpjavadevtools.server.core.operation;

/** Canonical expected failure fingerprint fields supplied by the caller. */
public record FailureExpectedFingerprintArguments(
        String exceptionType,
        String rootCauseType,
        String nearestApplicationMethodKey) {
}
