package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target;

import java.util.List;

/**
 * Deterministically ordered inferred target candidate.
 *
 * @param file project-relative source file
 * @param className simple class name
 * @param fqcn fully qualified class name when available
 * @param methodName method name
 * @param signature normalized method signature
 * @param returnsBoolean whether the method returns boolean
 * @param line selected executable line
 * @param declarationLine declaration line
 * @param endLine method end line
 * @param firstExecutableLine statically identified first executable line
 * @param lineSelectionStatus runtime line-selection status
 * @param lineSelectionSource runtime line-selection source
 * @param key method Probe key when available
 * @param reasons deterministic ranking reasons
 */
public record RouteTargetCandidate(
        String file,
        String className,
        String fqcn,
        String methodName,
        String signature,
        boolean returnsBoolean,
        Integer line,
        int declarationLine,
        int endLine,
        int firstExecutableLine,
        String lineSelectionStatus,
        String lineSelectionSource,
        String key,
        List<String> reasons) {

    /**
     * Defensively copies ranking reasons.
     */
    public RouteTargetCandidate {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /**
     * Returns a copy with a runtime-selected line.
     *
     * @param selectedLine selected line, or null when unresolved
     * @param status line-selection status
     * @param source line-selection source
     * @return updated candidate
     */
    public RouteTargetCandidate withLineSelection(
            Integer selectedLine,
            String status,
            String source) {
        return new RouteTargetCandidate(
                file,
                className,
                fqcn,
                methodName,
                signature,
                returnsBoolean,
                selectedLine,
                declarationLine,
                endLine,
                firstExecutableLine,
                status,
                source,
                key,
                reasons);
    }
}
