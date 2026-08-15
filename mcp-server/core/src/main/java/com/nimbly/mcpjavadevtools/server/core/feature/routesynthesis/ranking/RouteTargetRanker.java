package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import java.util.List;

/**
 * Explicit stable candidate ordering and ambiguity policy boundary.
 */
@FunctionalInterface
public interface RouteTargetRanker {

    /**
     * Orders candidates without relying on filesystem or map iteration order.
     *
     * @param candidates candidate set
     * @return deterministically ordered candidates
     */
    List<RouteTargetCandidate> rank(List<RouteTargetCandidate> candidates);
}
