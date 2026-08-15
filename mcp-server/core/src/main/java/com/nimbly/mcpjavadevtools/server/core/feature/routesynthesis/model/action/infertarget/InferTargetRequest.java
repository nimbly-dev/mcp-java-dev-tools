package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import java.util.List;

/**
 * Typed input for ranked Route Synthesis target inference.
 *
 * @param projectRootAbs selected project root
 * @param additionalSourceRoots optional additional source roots
 * @param classHint exact class or FQCN hint
 * @param methodHint exact method hint
 * @param lineHint optional declaration or executable line
 * @param maxCandidates maximum returned candidates
 * @param probeId optional Probe registry selector
 * @param probeBaseUrl optional explicit Probe route
 */
public record InferTargetRequest(
        String projectRootAbs,
        List<String> additionalSourceRoots,
        String classHint,
        String methodHint,
        Integer lineHint,
        Integer maxCandidates,
        String probeId,
        String probeBaseUrl) implements RouteSynthesisRequest {

    /**
     * Defensively copies optional source roots.
     */
    public InferTargetRequest {
        additionalSourceRoots = additionalSourceRoots == null
                ? List.of()
                : List.copyOf(additionalSourceRoots);
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.INFER_TARGET;
    }
}
