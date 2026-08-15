package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime;

/** Bounded runtime line-resolution evidence. */
public record RouteSynthesisRuntimeLineResolution(
        boolean resolved,
        Integer line,
        String status,
        String source) {

    /** Creates resolved runtime evidence. */
    public static RouteSynthesisRuntimeLineResolution resolved(int line) {
        return new RouteSynthesisRuntimeLineResolution(
                true, line, "validated", "runtime_probe_validation");
    }

    /** Creates unresolved runtime evidence. */
    public static RouteSynthesisRuntimeLineResolution unresolved(String status) {
        return new RouteSynthesisRuntimeLineResolution(
                false, null, status, "runtime_probe_validation");
    }
}
