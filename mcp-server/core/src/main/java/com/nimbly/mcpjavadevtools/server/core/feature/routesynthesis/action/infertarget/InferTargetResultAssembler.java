package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Assembles the stable infer_target output model. */
public class InferTargetResultAssembler {

    /** Builds the bounded infer_target payload. */
    public InferTargetResult assemble(
            Path projectRoot,
            RouteSynthesisWorkspaceSnapshot workspace,
            InferTargetRequest request,
            JavaSourceIndex index,
            List<RouteTargetCandidate> candidates) {
        List<String> additionalRoots = request.additionalSourceRoots().stream()
                .map(root -> RouteSynthesisPathPolicy.resolveExistingDirectory(
                        workspace.workspaceRoot(), root))
                .flatMap(Optional::stream)
                .map(Path::toString)
                .toList();
        RouteTargetHints hints = new RouteTargetHints(
                projectRoot.toString(),
                request.classHint(),
                request.methodHint(),
                request.lineHint(),
                "ranked_candidates");
        return new InferTargetResult(
                projectRoot.toString(),
                hints,
                additionalRoots,
                index.scannedJavaFiles(),
                limitCandidates(candidates, request));
    }

    private List<RouteTargetCandidate> limitCandidates(
            List<RouteTargetCandidate> candidates,
            InferTargetRequest request) {
        int requested = request.maxCandidates() == null ? 8 : request.maxCandidates();
        int limit = Math.max(1, Math.min(requested, 20));
        return candidates.stream().limit(limit).toList();
    }
}
