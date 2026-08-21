package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded HTTP exchange values required for redirect and result handling. */
public record HttpExchangeResponse(
        int statusCode,
        Map<String, String> headers,
        String body,
        String redirectLocation) {

    /** Defensively retains the normalized response headers. */
    public HttpExchangeResponse {
        headers = Map.copyOf(new LinkedHashMap<>(headers));
    }
}
