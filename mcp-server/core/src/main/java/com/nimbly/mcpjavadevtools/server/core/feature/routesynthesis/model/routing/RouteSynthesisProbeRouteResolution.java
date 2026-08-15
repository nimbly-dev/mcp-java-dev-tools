package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing;

/** Safe result of Probe route resolution. */
public record RouteSynthesisProbeRouteResolution(
        boolean resolved,
        String reasonCode,
        String baseUrl) {

    /** Creates a resolved route. */
    public static RouteSynthesisProbeRouteResolution resolved(String baseUrl) {
        return new RouteSynthesisProbeRouteResolution(true, null, baseUrl);
    }

    /** Creates an unresolved route without exposing configuration values. */
    public static RouteSynthesisProbeRouteResolution unresolved(String reasonCode) {
        return new RouteSynthesisProbeRouteResolution(false, reasonCode, null);
    }
}
