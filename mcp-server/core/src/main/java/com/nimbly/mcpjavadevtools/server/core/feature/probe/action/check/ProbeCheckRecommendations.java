package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives bounded diagnostic guidance from the two check endpoint outcomes.
 */
public final class ProbeCheckRecommendations {

    private static final String DIAGNOSTIC_KEY = "mcp.jvm.diagnose#key";

    private ProbeCheckRecommendations() {
    }

    public static List<String> forEndpoints(ProbeCheckEndpointResult reset, ProbeCheckEndpointResult status) {
        List<String> recommendations = new ArrayList<>();
        if (reset.status() == ProbeCheckEndpointStatus.UNAUTHORIZED) {
            recommendations.add("Probe reset endpoint is protected. Provide auth headers via probe.input.http.headers.");
        }
        if (reset.status() == ProbeCheckEndpointStatus.UNREACHABLE) {
            recommendations.add(
                    "Probe reset endpoint unreachable. Confirm docker service is running and probe port mapping is correct.");
        }
        if (status.status() == ProbeCheckEndpointStatus.UNAUTHORIZED) {
            recommendations.add("Probe status endpoint is protected. Provide auth headers via probe.input.http.headers.");
        }
        if (status.status() == ProbeCheckEndpointStatus.UNREACHABLE) {
            recommendations.add(
                    "Probe status endpoint unreachable. If port is unknown, ask user which service probe port is mapped.");
        }
        if (status.available() && !DIAGNOSTIC_KEY.equals(status.responseKey())) {
            recommendations.add(
                    "Probe key decoding mismatch detected. Rebuild/redeploy java-agent so query keys with # are decoded correctly.");
        }
        return List.copyOf(recommendations);
    }
}
