package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed input for bounded Probe availability diagnostics.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param headers optional protected-endpoint request headers
 * @param timeout optional per-request timeout
 */
public record ProbeCheckRequest(
        ProbeTargetSelector targetSelector,
        Map<String, String> headers,
        Duration timeout) implements ProbeRequest {

    /**
     * Defensively copies optional headers; endpoint limits validate protocol safety.
     */
    public ProbeCheckRequest {
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
    }

    @Override
    public ProbeAction action() {
        return ProbeAction.CHECK;
    }
}
