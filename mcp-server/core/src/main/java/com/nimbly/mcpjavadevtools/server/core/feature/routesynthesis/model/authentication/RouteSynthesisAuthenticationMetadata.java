package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication;

import java.util.List;

/** Public authentication metadata with no secret material. */
public record RouteSynthesisAuthenticationMetadata(
        String status,
        String strategy,
        List<String> missing,
        List<String> headers,
        String source) {

    /** Keeps the original four-field construction seam source-compatible. */
    public RouteSynthesisAuthenticationMetadata(
            String status,
            String strategy,
            List<String> missing,
            List<String> headers) {
        this(status, strategy, missing, headers, null);
    }

    /** Defensively copies safe metadata lists. */
    public RouteSynthesisAuthenticationMetadata {
        missing = missing == null ? List.of() : List.copyOf(missing);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }
}
