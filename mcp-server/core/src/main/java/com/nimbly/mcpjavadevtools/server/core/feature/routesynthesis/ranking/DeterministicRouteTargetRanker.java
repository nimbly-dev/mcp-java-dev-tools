package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import java.util.Comparator;
import java.util.List;

/**
 * Stable ordering policy matching the TypeScript target ranking tie-breakers.
 */
public class DeterministicRouteTargetRanker implements RouteTargetRanker {

    /**
     * Orders by class identity, method identity, line, and file path.
     */
    @Override
    public List<RouteTargetCandidate> rank(List<RouteTargetCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparing((RouteTargetCandidate candidate) ->
                                (candidate.fqcn() == null ? candidate.className() : candidate.fqcn()).toLowerCase())
                        .thenComparing(candidate -> (candidate.methodName() == null
                                ? "" : candidate.methodName()).toLowerCase())
                        .thenComparing(candidate -> candidate.line() == null
                                ? Integer.MAX_VALUE : candidate.line())
                        .thenComparing(RouteTargetCandidate::file))
                .toList();
    }
}
