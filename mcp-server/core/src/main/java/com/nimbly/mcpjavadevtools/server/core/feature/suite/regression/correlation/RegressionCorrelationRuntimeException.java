package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

/** Stable fail-closed runtime correlation failure. */
public class RegressionCorrelationRuntimeException extends RuntimeException {

    private final String reasonCode;

    /** Creates a failure with a non-sensitive reason code. */
    public RegressionCorrelationRuntimeException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    /** Returns the stable failure reason. */
    public String reasonCode() {
        return reasonCode;
    }
}
