package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import java.util.List;

/** Resolves and filters runtime-backed lines for inferred candidates. */
public class InferTargetRuntimeLineResolver {

    private final RouteSynthesisProbeRouteResolver probeRouteResolver;
    private final RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider;

    /** Creates the runtime-line resolver. */
    public InferTargetRuntimeLineResolver(
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider) {
        this.probeRouteResolver = probeRouteResolver;
        this.runtimeEvidenceProvider = runtimeEvidenceProvider;
    }

    /** Resolves runtime lines for all source candidates. */
    public List<RouteTargetCandidate> resolve(
            List<RouteTargetCandidate> candidates,
            InferTargetRequest request) {
        RouteSynthesisProbeRouteResolution route = probeRouteResolver.resolve(
                request.probeId(), request.probeBaseUrl());
        return candidates.stream().map(candidate -> resolve(candidate, route)).toList();
    }

    /** Keeps candidates whose runtime line is validated and honors an optional line hint. */
    public List<RouteTargetCandidate> select(
            List<RouteTargetCandidate> candidates,
            Integer lineHint) {
        List<RouteTargetCandidate> resolved = candidates.stream()
                .filter(this::hasResolvedLine)
                .toList();
        if (lineHint == null) {
            return resolved;
        }
        return resolved.stream()
                .filter(candidate -> lineHint.equals(candidate.line()))
                .toList();
    }

    /** Reports whether a candidate has bounded, validated runtime evidence. */
    public boolean hasResolvedLine(RouteTargetCandidate candidate) {
        return candidate.line() != null && "validated".equals(candidate.lineSelectionStatus());
    }

    private RouteTargetCandidate resolve(
            RouteTargetCandidate candidate,
            RouteSynthesisProbeRouteResolution route) {
        if (!route.resolved()) {
            return candidate.withLineSelection(null, "unresolved", "runtime_probe_validation");
        }
        RouteSynthesisRuntimeLineResolution evidence = runtimeEvidenceProvider.resolveLine(
                candidate.key(), candidate.declarationLine(), candidate.endLine(), route);
        return candidate.withLineSelection(evidence.line(), evidence.status(), evidence.source());
    }
}
