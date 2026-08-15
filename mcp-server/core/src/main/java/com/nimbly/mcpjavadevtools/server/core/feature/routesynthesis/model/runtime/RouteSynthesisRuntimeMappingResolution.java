package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import java.util.List;

/** Bounded result of resolving a runtime request mapping. */
public record RouteSynthesisRuntimeMappingResolution(
        String status,
        String reasonCode,
        String failedStep,
        String nextAction,
        RouteSynthesisRecipeCandidate requestCandidate,
        List<String> evidence,
        List<String> attemptedStrategies) {

    /** Creates a successful runtime mapping result. */
    public static RouteSynthesisRuntimeMappingResolution success(
            RouteSynthesisRecipeCandidate candidate,
            List<String> evidence,
            List<String> attemptedStrategies) {
        return new RouteSynthesisRuntimeMappingResolution(
                "ok", null, null, null, candidate, copy(evidence), copy(attemptedStrategies));
    }

    /** Creates a deterministic runtime mapping report. */
    public static RouteSynthesisRuntimeMappingResolution failure(
            String reasonCode,
            String failedStep,
            String nextAction,
            List<String> evidence,
            List<String> attemptedStrategies) {
        return new RouteSynthesisRuntimeMappingResolution(
                "report", reasonCode, failedStep, nextAction, null, copy(evidence), copy(attemptedStrategies));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
