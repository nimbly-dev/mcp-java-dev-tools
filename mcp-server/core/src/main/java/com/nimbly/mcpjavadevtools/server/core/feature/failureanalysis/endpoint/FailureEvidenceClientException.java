package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

/** Bounded technical exception from the Sidecar evidence transport. */
public final class FailureEvidenceClientException extends RuntimeException {

    private final FailureEvidenceFailureKind failureKind;

    public FailureEvidenceClientException(FailureEvidenceFailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    public FailureEvidenceClientException(
            FailureEvidenceFailureKind failureKind, String message, Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
    }

    /** @return technical kind without exposing it in public output */
    public FailureEvidenceFailureKind failureKind() {
        return failureKind;
    }
}
