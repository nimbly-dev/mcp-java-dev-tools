package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated, bounded HTTP request ready for execution. */
public record ValidatedHttpRequest(
        URI uri,
        String method,
        Map<String, String> headers,
        byte[] body,
        int timeoutMillis) {

    /** Defensively retains the validated request values. */
    public ValidatedHttpRequest {
        headers = Map.copyOf(new LinkedHashMap<>(headers));
        body = body.clone();
    }

    /** {@inheritDoc} */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
