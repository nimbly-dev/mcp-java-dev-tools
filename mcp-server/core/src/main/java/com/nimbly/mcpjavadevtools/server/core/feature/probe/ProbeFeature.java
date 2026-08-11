package com.nimbly.mcpjavadevtools.server.core.feature.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;

/**
 * Intentional Core entry point for consolidated Probe behavior.
 *
 * <p>Implementations are introduced by bounded action stories. This boundary
 * must not expose Spring Boot, Spring AI, or MCP SDK types.</p>
 */
public interface ProbeFeature {

    /**
     * Executes one typed Probe request.
     *
     * @param request feature-owned Probe request
     * @return deterministic Probe result
     */
    ProbeResult execute(ProbeRequest request);
}
