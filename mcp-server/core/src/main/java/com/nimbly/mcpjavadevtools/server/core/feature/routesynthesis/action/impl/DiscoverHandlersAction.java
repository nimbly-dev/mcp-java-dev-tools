package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers.DiscoverHandlersFailureReporter;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers.DiscoverHandlersRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers.DiscoverHandlersResultAssembler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers.DiscoverHandlersRuntimeLineResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.RouteSynthesisHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Orchestrates the bounded discover_handlers action. */
public class DiscoverHandlersAction implements RouteSynthesisActionHandler {

    private final RouteSynthesisWorkspaceProvider workspaceProvider;
    private final RouteSynthesisHandlerDiscovery handlerDiscovery;
    private final RouteSynthesisProbeRouteResolver probeRouteResolver;
    private final DiscoverHandlersRequestPolicy requestPolicy;
    private final DiscoverHandlersFailureReporter failureReporter;
    private final DiscoverHandlersRuntimeLineResolver runtimeLineResolver;
    private final DiscoverHandlersResultAssembler resultAssembler;

    /** Creates the action with purpose-owned collaborators. */
    public DiscoverHandlersAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            RouteSynthesisHandlerDiscovery handlerDiscovery,
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider) {
        this.workspaceProvider = workspaceProvider;
        this.handlerDiscovery = handlerDiscovery;
        this.probeRouteResolver = probeRouteResolver;
        this.requestPolicy = new DiscoverHandlersRequestPolicy();
        this.failureReporter = new DiscoverHandlersFailureReporter();
        this.runtimeLineResolver = new DiscoverHandlersRuntimeLineResolver(runtimeEvidenceProvider);
        this.resultAssembler = new DiscoverHandlersResultAssembler();
    }

    /** Keeps the feature unit-test seam deterministic and source-free. */
    public DiscoverHandlersAction() {
        this(() -> Optional.empty(), (root, additional, hint) ->
                RouteSynthesisHandlerDiscoveryResult.failure(
                        "workspace_context_missing", "workspace_resolution", "bind_workspace_root", List.of()),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.unresolved("probe_id_required"),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.unresolved(
                        "runtime_validation_not_requested"));
    }

    @Override
    public RouteSynthesisResult execute(RouteSynthesisRequest request) {
        if (!(request instanceof DiscoverHandlersRequest discoverRequest)) {
            return failureReporter.invalidRequest();
        }
        Optional<RouteSynthesisWorkspaceSnapshot> workspace = workspaceProvider.current();
        if (workspace.isEmpty()) {
            return failureReporter.workspaceMissing();
        }
        return executeBound(discoverRequest, workspace.get());
    }

    private RouteSynthesisResult executeBound(
            DiscoverHandlersRequest request,
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
        RouteSynthesisHandlerDiscoveryResult discovered = handlerDiscovery.discover(
                projectRoot.get(), additionalRoots.get(), request.classHint());
        if (!"ok".equals(discovered.status())) {
            return failureReporter.discoveryFailure(discovered);
        }
        RouteSynthesisProbeRouteResolution route = probeRouteResolver.resolve(
                request.probeId(), request.probeBaseUrl());
        List<RouteSynthesisHandler> handlers = runtimeLineResolver.resolve(discovered.handlers(), route);
        return resultAssembler.assemble(
                projectRoot.get(), request, additionalRoots.get(), discovered, handlers,
                runtimeLineResolver.allValidated(handlers));
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.DISCOVER_HANDLERS;
    }
}
