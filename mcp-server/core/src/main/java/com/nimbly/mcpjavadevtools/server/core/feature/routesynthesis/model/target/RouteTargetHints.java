package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target;

/**
 * Safe request hints echoed by Route Synthesis deterministic results.
 *
 * @param projectRootAbs normalized project root
 * @param classHint requested class hint
 * @param methodHint requested method hint
 * @param lineHint requested line hint
 * @param discoveryMode selected discovery mode
 */
public record RouteTargetHints(
        String projectRootAbs,
        String classHint,
        String methodHint,
        Integer lineHint,
        String discoveryMode) {
}
