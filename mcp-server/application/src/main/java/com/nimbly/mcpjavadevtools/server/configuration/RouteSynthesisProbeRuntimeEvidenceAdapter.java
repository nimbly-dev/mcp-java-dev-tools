package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeCapture;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import java.time.Duration;

/**
 * Uses the public Probe Feature boundary for bounded runtime line evidence.
 */
class RouteSynthesisProbeRuntimeEvidenceAdapter implements RouteSynthesisRuntimeEvidenceProvider {

    private final ProbeFeature probeFeature;

    RouteSynthesisProbeRuntimeEvidenceAdapter(ProbeFeature probeFeature) {
        this.probeFeature = probeFeature;
    }

    @Override
    public RouteSynthesisRuntimeLineResolution resolveLine(
            String methodKey,
            int startLine,
            int endLine,
            RouteSynthesisProbeRouteResolution route) {
        if (!route.resolved()) {
            return RouteSynthesisRuntimeLineResolution.unresolved("runtime_route_unresolved");
        }
        try {
            ProbeRequest request = new ProbeSingleStatusRequest(
                    new ProbeTargetSelector(null, route.baseUrl()),
                    new ProbeKeySelector(methodKey, startLine), Duration.ofSeconds(1));
            ProbeResult result = probeFeature.execute(request);
            return lineEvidence(result, startLine, endLine);
        } catch (RuntimeException exception) {
            return RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved");
        }
    }

    @Override
    public RouteSynthesisRuntimeCapture capture(
            String methodKey,
            int line,
            RouteSynthesisProbeRouteResolution route) {
        if (!route.resolved()) {
            return RouteSynthesisRuntimeCapture.unavailable("runtime_route_unresolved");
        }
        try {
            ProbeRequest request = new ProbeSingleStatusRequest(
                    new ProbeTargetSelector(null, route.baseUrl()),
                    new ProbeKeySelector(methodKey, line), Duration.ofSeconds(2));
            ProbeResult result = probeFeature.execute(request);
            return captureEvidence(result);
        } catch (RuntimeException exception) {
            return RouteSynthesisRuntimeCapture.unavailable("runtime_capture_unavailable");
        }
    }

    private RouteSynthesisRuntimeCapture captureEvidence(ProbeResult result) {
        if (result == null || result.actionResult().isEmpty()
                || !(result.actionResult().orElseThrow() instanceof ProbeStatusResult status)
                || status.entries().isEmpty()) {
            return RouteSynthesisRuntimeCapture.unavailable("runtime_capture_unavailable");
        }
        ProbeStatusEntry entry = status.entries().get(0);
        if ("invalid_line_target".equals(entry.lineValidation())
                || Boolean.FALSE.equals(entry.lineResolvable())) {
            return RouteSynthesisRuntimeCapture.unavailable("invalid_line_target");
        }
        if (entry.capturePreview() == null) {
            return RouteSynthesisRuntimeCapture.available(entry.lineValidation(), entry.lineResolvable(),
                    null, null, java.util.List.of());
        }
        return RouteSynthesisRuntimeCapture.available(entry.lineValidation(), entry.lineResolvable(),
                entry.capturePreview().captureId(), entry.capturePreview().capturedAtEpoch(),
                entry.capturePreview().executionPaths());
    }

    private RouteSynthesisRuntimeLineResolution lineEvidence(
            ProbeResult result,
            int startLine,
            int endLine) {
        if (result == null || result.actionResult().isEmpty()) {
            return RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved");
        }
        ProbeActionResult action = result.actionResult().orElseThrow();
        if (!(action instanceof ProbeStatusResult status) || status.entries().isEmpty()) {
            return RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved");
        }
        ProbeStatusEntry entry = status.entries().get(0);
        Integer line = lineFromKey(entry.key());
        if (Boolean.TRUE.equals(entry.lineResolvable()) && line != null
                && line >= startLine && line <= endLine) {
            return RouteSynthesisRuntimeLineResolution.resolved(line);
        }
        return RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved");
    }

    private Integer lineFromKey(String key) {
        if (key == null) {
            return null;
        }
        int separator = key.lastIndexOf(':');
        if (separator < 0 || separator == key.length() - 1) {
            return null;
        }
        try {
            return Integer.valueOf(key.substring(separator + 1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
