package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime;

import java.util.List;

/** Sanitized runtime capture enrichment returned by the public Probe boundary. */
public record RouteSynthesisRuntimeCapture(
        String status,
        String reason,
        String lineValidation,
        Boolean lineResolvable,
        String captureId,
        Long capturedAtEpoch,
        List<String> executionPaths) {

    /** Creates a bounded unavailable capture result. */
    public static RouteSynthesisRuntimeCapture unavailable(String reason) {
        return new RouteSynthesisRuntimeCapture(
                "unavailable", reason, null, null, null, null, List.of());
    }

    /** Creates a bounded available capture result. */
    public static RouteSynthesisRuntimeCapture available(
            String lineValidation,
            Boolean lineResolvable,
            String captureId,
            Long capturedAtEpoch,
            List<String> executionPaths) {
        return new RouteSynthesisRuntimeCapture(
                "available", null, lineValidation, lineResolvable, captureId, capturedAtEpoch,
                executionPaths == null ? List.of() : List.copyOf(executionPaths));
    }
}
