package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods.ClassMethodsClassSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods.ClassMethodsFailureReporter;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods.ClassMethodsRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods.ClassMethodsResultAssembler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods.ClassMethodsRuntimeLineResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.JavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Orchestrates the bounded class_methods action. */
public class ClassMethodsAction implements RouteSynthesisActionHandler {

    private final RouteSynthesisWorkspaceProvider workspaceProvider;
    private final JavaSourceDiscovery sourceDiscovery;
    private final ClassMethodsClassSelector classSelector;
    private final ClassMethodsRequestPolicy requestPolicy;
    private final ClassMethodsFailureReporter failureReporter;
    private final ClassMethodsRuntimeLineResolver runtimeLineResolver;
    private final ClassMethodsResultAssembler resultAssembler;

    /**
     * Creates the action with purpose-owned collaborators.
     *
     * @param workspaceProvider bound workspace provider
     * @param sourceDiscovery contained source discovery
     * @param probeRouteResolver Probe route resolver
     * @param runtimeEvidenceProvider bounded runtime evidence provider
     */
    public ClassMethodsAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            JavaSourceDiscovery sourceDiscovery,
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider) {
        this.workspaceProvider = workspaceProvider;
        this.sourceDiscovery = sourceDiscovery;
        this.classSelector = new ClassMethodsClassSelector();
        this.requestPolicy = new ClassMethodsRequestPolicy();
        this.failureReporter = new ClassMethodsFailureReporter();
        this.runtimeLineResolver = new ClassMethodsRuntimeLineResolver(
                probeRouteResolver, runtimeEvidenceProvider);
        this.resultAssembler = new ClassMethodsResultAssembler();
    }

    @Override
    public RouteSynthesisResult execute(RouteSynthesisRequest request) {
        if (!(request instanceof ClassMethodsRequest classMethodsRequest)) {
            return failureReporter.invalidRequest();
        }
        Optional<RouteSynthesisWorkspaceSnapshot> workspace = workspaceProvider.current();
        if (workspace.isEmpty()) {
            return failureReporter.workspaceMissing();
        }
        return executeBound(classMethodsRequest, workspace.get());
    }

    private RouteSynthesisResult executeBound(
            ClassMethodsRequest request,
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
        List<JavaSourceFile> matches = classSelector.select(index, request.classHint());
        if (matches.isEmpty()) {
            return resultAssembler.notFound(projectRoot.get(), request, additionalRoots.get(), index);
        }
        if (matches.size() > 1) {
            return resultAssembler.ambiguous(
                    projectRoot.get(), request, additionalRoots.get(), index, matches);
        }
        return resultAssembler.success(
                projectRoot.get(), request, additionalRoots.get(), index,
                runtimeLineResolver.resolve(projectRoot.get(), matches.get(0), request));
    }

    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.CLASS_METHODS;
    }
}
