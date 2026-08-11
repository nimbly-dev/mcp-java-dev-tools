package com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint;

/**
 * Closed endpoint-client failures that Core actions may intentionally normalize.
 */
public enum ProbeEndpointFailureKind {

    UNREACHABLE,
    RESPONSE_LIMIT_EXCEEDED,
    RESPONSE_READ_FAILED,
    INTERRUPTED
}
