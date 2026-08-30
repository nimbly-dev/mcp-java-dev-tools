package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation;

import java.util.Map;

/** Canonical protected HTTP headers accepted by the Probe check operation. */
public record ProbeHttpArguments(Map<String, String> headers) {

    public ProbeHttpArguments {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
