package com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint;

/**
 * Bounded endpoint-client failure that action behavior converts into a safe result.
 */
public class ProbeEndpointClientException extends RuntimeException {

    private final ProbeEndpointFailureKind failureKind;

    /**
     * Creates a sanitized endpoint-client failure without exposing request secrets.
     *
     * @param message controlled failure description
     * @param cause underlying transport failure
     */
    public ProbeEndpointClientException(
            ProbeEndpointFailureKind failureKind,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
    }

    /**
     * Creates a controlled endpoint-client failure without a nested cause.
     *
     * @param message controlled failure description
     */
    public ProbeEndpointClientException(ProbeEndpointFailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    /**
     * Returns the closed failure category safe for Core action normalization.
     *
     * @return endpoint failure category
     */
    public ProbeEndpointFailureKind failureKind() {
        return failureKind;
    }
}
