package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

/** Technical failure classifications normalized by the Failure Analysis actions. */
public enum FailureEvidenceFailureKind {
    INVALID_ENDPOINT,
    UNREACHABLE,
    TIMEOUT,
    INTERRUPTED,
    RESPONSE_READ_FAILED,
    RESPONSE_LIMIT_EXCEEDED,
    REQUEST_SERIALIZATION_FAILED
}
