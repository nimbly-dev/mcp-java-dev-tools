package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target;

/**
 * Source-level method span and deterministic line-selection metadata.
 *
 * @param methodName method name
 * @param signature normalized source signature
 * @param startLine declaration line
 * @param endLine closing-brace line
 * @param firstExecutableLine first statically identified executable line
 * @param lineSelectionStatus runtime line-selection status
 * @param lineSelectionSource runtime line-selection source
 * @param probeKey runtime Probe method key
 */
public record RouteTargetMethod(
        String methodName,
        String signature,
        int startLine,
        int endLine,
        int firstExecutableLine,
        String lineSelectionStatus,
        String lineSelectionSource,
        String probeKey) {
}
