package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget.InferTargetCandidateResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget.InferTargetFailureReporter;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget.InferTargetResultAssembler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget.InferTargetRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget.InferTargetRuntimeLineResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.JavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisDisambiguationDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.RouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Orchestrates the bounded infer_target action. */
public class InferTargetAction implements RouteSynthesisActionHandler {

    private final RouteSynthesisWorkspaceProvider workspaceProvider;
    private final JavaSourceDiscovery sourceDiscovery;
    private final InferTargetCandidateResolver candidateResolver;
    private final InferTargetRequestPolicy requestPolicy;
    private final InferTargetFailureReporter failureReporter;
    private final InferTargetRuntimeLineResolver runtimeLineResolver;
    private final InferTargetResultAssembler resultAssembler;
    private final RouteTargetRanker targetRanker;

    /**
     * Creates the action with purpose-owned collaborators.
     *
     * @param workspaceProvider bound workspace provider
     * @param sourceDiscovery contained source discovery
     * @param probeRouteResolver Probe route resolver
     * @param runtimeEvidenceProvider bounded runtime evidence provider
     * @param targetRanker stable target ranking policy
     */
    public InferTargetAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            JavaSourceDiscovery sourceDiscovery,
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider,
            RouteTargetRanker targetRanker) {
        this.workspaceProvider = workspaceProvider;
        this.sourceDiscovery = sourceDiscovery;
        this.candidateResolver = new InferTargetCandidateResolver();
        this.requestPolicy = new InferTargetRequestPolicy();
        this.failureReporter = new InferTargetFailureReporter();
        this.runtimeLineResolver = new InferTargetRuntimeLineResolver(
                probeRouteResolver, runtimeEvidenceProvider);
        this.resultAssembler = new InferTargetResultAssembler();
        this.targetRanker = targetRanker;
    }

    @Override
    public RouteSynthesisResult execute(RouteSynthesisRequest request) {
        if (!(request instanceof InferTargetRequest targetRequest)) {
            return failureReporter.invalidRequest();
        }
        Optional<RouteSynthesisWorkspaceSnapshot> workspace = workspaceProvider.current();
        if (workspace.isEmpty()) {
            return failureReporter.workspaceMissing();
        }
        return executeBound(targetRequest, workspace.get());
    }

    private RouteSynthesisResult executeBound(
            InferTargetRequest request,
            RouteSynthesisWorkspaceSnapshot workspace) {
        Optional<RouteSynthesisResult> invalid = requestPolicy.validate(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Optional<Path> projectRoot = requestPolicy.resolveProjectRoot(request, workspace);
        if (projectRoot.isEmpty()) {
            return failureReporter.invalidProjectRoot();
        }
        Optional<List<Path>> additionalRoots = requestPolicy.resolveAdditionalRoots(request, workspace);
        if (additionalRoots.isEmpty()) {
            return failureReporter.invalidAdditionalRoots();
        }
        JavaSourceIndex index = sourceDiscovery.discover(
                projectRoot.get(), additionalRoots.get(), request.classHint());
        List<RouteTargetCandidate> candidates = candidateResolver.resolve(index, projectRoot.get(), request);
        if (candidates.isEmpty()) {
            return failureReporter.targetNotFound();
        }
        List<RouteTargetCandidate> resolved = runtimeLineResolver.resolve(candidates, request);
        if (resolved.stream().noneMatch(runtimeLineResolver::hasResolvedLine)) {
            return failureReporter.runtimeLineUnresolved(resolved.size());
        }
        List<RouteTargetCandidate> lineSelected = runtimeLineResolver.select(resolved, request.lineHint());
        if (lineSelected.isEmpty()) {
            return failureReporter.lineHintNotResolvable();
        }
        List<RouteTargetCandidate> ranked = targetRanker.rank(lineSelected);
        InferTargetResult output = resultAssembler.assemble(
                projectRoot.get(), workspace, request, index, ranked);
        if (ranked.size() > 1) {
            return RouteSynthesisResult.disambiguation(new RouteSynthesisDisambiguationDetails(
                    "disambiguation", "target_ambiguous", "target_ambiguous", "target_selection",
                    "disambiguate_target", "Refine classHint or methodHint to select one target.", output));
        }
        return RouteSynthesisResult.success("ranked_candidates", output);
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.INFER_TARGET;
    }
}
