package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeCollaborators;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeExecutionPlanBuilder;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeFailureReporter;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeInputValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeResultAssembler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeRuntimeResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeTemplateModelBuilder;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.DefaultRouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.RouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.RouteSynthesisHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeAssemblyInput;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.DefaultRouteSynthesisRuntimeMappingsProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template.DefaultRouteSynthesisRecipeTemplateRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.SynthesizerRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Orchestrates bounded create_recipe collaborators and deterministic result flow. */
public class CreateRecipeAction implements RouteSynthesisActionHandler {

    private final RouteSynthesisWorkspaceProvider workspaceProvider;
    private final RouteSynthesisHandlerDiscovery handlerDiscovery;
    private final SynthesizerRegistry synthesizerRegistry;
    private final CreateRecipeInputValidator inputValidator;
    private final CreateRecipeRuntimeResolver runtimeResolver;
    private final CreateRecipeFailureReporter failureReporter;
    private final CreateRecipeResultAssembler resultAssembler;

    /** Creates recipe behavior with the complete Core collaborator boundary. */
    public CreateRecipeAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            RouteSynthesisHandlerDiscovery handlerDiscovery,
            RouteSynthesisAuthenticationResolver authenticationResolver,
            SynthesizerRegistry synthesizerRegistry) {
        this(workspaceProvider, handlerDiscovery, authenticationResolver, synthesizerRegistry,
                new CreateRecipeCollaborators(new DefaultRouteSynthesisRuntimeMappingsProvider(), null,
                        new DefaultRouteSynthesisRecipeTemplateRenderer(),
                        (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution
                                .unresolved("probe_route_unresolved")));
    }

    /** Creates recipe behavior with explicit runtime and template collaborators. */
    public CreateRecipeAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            RouteSynthesisHandlerDiscovery handlerDiscovery,
            RouteSynthesisAuthenticationResolver authenticationResolver,
            SynthesizerRegistry synthesizerRegistry,
            CreateRecipeCollaborators collaborators) {
        this.workspaceProvider = workspaceProvider;
        this.handlerDiscovery = handlerDiscovery;
        this.synthesizerRegistry = synthesizerRegistry;
        this.inputValidator = new CreateRecipeInputValidator();
        this.runtimeResolver = new CreateRecipeRuntimeResolver(collaborators.runtimeMappingsProvider());
        this.failureReporter = new CreateRecipeFailureReporter();
        this.resultAssembler = new CreateRecipeResultAssembler(
                authenticationResolver, collaborators.runtimeEvidenceProvider(),
                collaborators.templateRenderer(), collaborators.probeRouteResolver(),
                new CreateRecipeExecutionPlanBuilder(), new CreateRecipeTemplateModelBuilder());
    }

    /** Keeps the existing focused external-policy test seam source-free. */
    public CreateRecipeAction(SynthesizerRegistry synthesizerRegistry) {
        this(() -> Optional.empty(), (root, additional, hint) ->
                        RouteSynthesisHandlerDiscoveryResult.failure(
                                "workspace_context_missing", "workspace_resolution", "bind_workspace_root", List.of()),
                new DefaultRouteSynthesisAuthenticationResolver(), synthesizerRegistry);
    }

    /** Executes deterministic recipe creation. */
    @Override
    public RouteSynthesisResult execute(RouteSynthesisRequest request) {
        if (!(request instanceof CreateRecipeRequest recipeRequest)) {
            return failureReporter.invalidRequest();
        }
        SynthesizerSelection selection = synthesizerRegistry.select(recipeRequest);
        if (selection.externalModulesConfigured()) {
            return failureReporter.externalPluginBlocker(selection);
        }
        if (!selection.compatible()) {
            return failureReporter.noCompatibleSynthesizer(selection);
        }
        Optional<RouteSynthesisWorkspaceSnapshot> workspace = workspaceProvider.current();
        if (workspace.isEmpty()) {
            return failureReporter.workspaceMissing();
        }
        return executeBound(recipeRequest, workspace.get());
    }

    /** Returns the handled action discriminator. */
    @Override
    public RouteSynthesisAction action() {
        return RouteSynthesisAction.CREATE_RECIPE;
    }

    private RouteSynthesisResult executeBound(
            CreateRecipeRequest request, RouteSynthesisWorkspaceSnapshot workspace) {
        Optional<RouteSynthesisResult> invalid = inputValidator.validate(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Optional<Path> projectRoot = RouteSynthesisPathPolicy.resolveExistingDirectory(
                workspace.workspaceRoot(), request.projectRootAbs());
        if (projectRoot.isEmpty()) {
            return failureReporter.targetMissing();
        }
        Optional<List<Path>> roots = inputValidator.resolveAdditionalRoots(request, workspace);
        if (roots.isEmpty()) {
            return failureReporter.invalidAdditionalRoots();
        }
        Optional<RouteSynthesisRuntimeMappingResolution> runtime = runtimeResolver.resolve(request);
        String discoveryPreference = request.discoveryPreference() == null
                ? "static_only" : request.discoveryPreference();
        if (runtime.isPresent() && "ok".equals(runtime.get().status())) {
            return resultAssembler.assembleRuntime(projectRoot.get(), request, roots.get(), runtime.get(),
                    runtimeResolver.handler(request, runtime.get().requestCandidate()));
        }
        if (runtime.isPresent() && "runtime_only".equals(discoveryPreference)) {
            return failureReporter.runtimeFailure(runtime.get(),
                    runtimeResolver.nextAction(runtime.get().nextAction()));
        }
        RouteSynthesisHandlerDiscoveryResult discovery = handlerDiscovery.discover(
                projectRoot.get(), roots.get(), request.classHint());
        if (!"ok".equals(discovery.status())) {
            return failureReporter.targetDiscoveryFailure(discovery);
        }
        if ("line_probe".equals(request.intentMode())
                && !"static_only".equals(discoveryPreference)
                && discovery.handlers().stream()
                .noneMatch(handler -> "validated".equals(handler.lineSelectionStatus()))) {
            return failureReporter.runtimeLineUnresolved();
        }
        RouteSynthesisSynthesisResult synthesized = synthesizerRegistry.synthesize(request, discovery);
        if (!synthesized.compatible()) {
            return RouteSynthesisResult.report(
                    "report", synthesized.reasonCode(), synthesized.failedStep(),
                    synthesized.nextActionCode(), "Refine the target or install a compatible Synthesizer.");
        }
        return resultAssembler.assemble(new RouteSynthesisRecipeAssemblyInput(
                projectRoot.get(), request, roots.get(), discovery, synthesized, runtime));
    }

}
