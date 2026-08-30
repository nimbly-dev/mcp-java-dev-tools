package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Canonical transport arguments with the public options object kept typed. */
public record TransportExecuteArguments(
        String protocol,
        Map<String, Object> request,
        TransportExecuteOptionsArguments options) {

    public TransportExecuteArguments {
        request = request == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(request));
    }
}
