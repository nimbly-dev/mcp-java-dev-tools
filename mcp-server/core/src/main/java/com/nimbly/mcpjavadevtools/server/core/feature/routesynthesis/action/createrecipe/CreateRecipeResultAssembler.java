package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.RouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeAssemblyInput;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisExecutionPlan;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeCapture;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.template.RouteSynthesisRecipeTemplateModel;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template.RouteSynthesisRecipeTemplateRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Assembles the deterministic recipe result after target and Synthesizer resolution. */
public class CreateRecipeResultAssembler {

    private final RouteSynthesisAuthenticationResolver authenticationResolver;
    private final RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider;
    private final RouteSynthesisRecipeTemplateRenderer templateRenderer;
    private final RouteSynthesisProbeRouteResolver probeRouteResolver;
    private final CreateRecipeExecutionPlanBuilder planBuilder;
    private final CreateRecipeTemplateModelBuilder templateModelBuilder;

    public CreateRecipeResultAssembler(
            RouteSynthesisAuthenticationResolver authenticationResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider,
            RouteSynthesisRecipeTemplateRenderer templateRenderer,
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            CreateRecipeExecutionPlanBuilder planBuilder,
            CreateRecipeTemplateModelBuilder templateModelBuilder) {
        this.authenticationResolver = authenticationResolver;
        this.runtimeEvidenceProvider = runtimeEvidenceProvider;
        this.templateRenderer = templateRenderer;
        this.probeRouteResolver = probeRouteResolver;
        this.planBuilder = planBuilder;
        this.templateModelBuilder = templateModelBuilder;
    }

    /** Builds the recipe envelope and preserves runtime evidence in bounded form. */
    public RouteSynthesisResult assemble(RouteSynthesisRecipeAssemblyInput input) {
        CreateRecipeRequest request = input.request();
        RouteSynthesisHandler handler = input.synthesized().selectedHandler();
        RouteSynthesisAuthenticationMetadata auth = authenticationResolver.resolve(request);
        RouteSynthesisRecipeCandidate candidate = candidate(input, handler);
        String selectedMode = "line_probe".equals(request.intentMode())
                ? "single_line_probe" : "regression";
        RouteSynthesisRuntimeCapture runtimeCapture = runtimeCapture(request, handler);
        if (invalidLineTarget(request, runtimeCapture)) {
            return RouteSynthesisResult.report(
                    "target_not_inferred", "runtime_line_unresolved", "line_validation",
                    "select_resolvable_line", "Select a runtime-resolvable line and rerun create_recipe.");
        }
        RouteSynthesisExecutionPlan plan = planBuilder.build(
                request, selectedMode, handler, candidate, auth);
        List<String> evidence = evidence(input, runtimeCapture);
        List<String> strategies = strategies(input);
        RouteSynthesisRecipeTemplateModel templateModel = templateModelBuilder.build(
                request, candidate, selectedMode, auth, plan);
        String rendered = templateRenderer.render(request.outputTemplate(), templateModel);
        RouteSynthesisRecipeResult output = new RouteSynthesisRecipeResult(
                input.projectRoot().toString(), new RouteTargetHints(
                        input.projectRoot().toString(), request.classHint(), request.methodHint(),
                        request.lineHint(), "spring_http"),
                input.roots().stream().map(Path::toString).toList(), "spring_http",
                input.synthesized().synthesizerUsed(), handler, List.of(candidate), plan, "ready",
                request.intentMode(), auth, evidence, strategies, runtimeCapture, rendered);
        return new RouteSynthesisResult(
                "recipe", "ready", null, null, null, null, output,
                output.evidence(), output.attemptedStrategies());
    }

    /** Assembles a recipe after a runtime mapping has supplied the request candidate. */
    public RouteSynthesisResult assembleRuntime(
            Path projectRoot,
            CreateRecipeRequest request,
            List<Path> roots,
            RouteSynthesisRuntimeMappingResolution runtime,
            RouteSynthesisHandler handler) {
        RouteSynthesisHandlerDiscoveryResult discovery = RouteSynthesisHandlerDiscoveryResult.success(
                request.classHint(), null, List.of(handler), 0, runtime.evidence());
        RouteSynthesisSynthesisResult synthesized = RouteSynthesisSynthesisResult.success(
                "spring_http", handler);
        return assemble(new RouteSynthesisRecipeAssemblyInput(
                projectRoot, request, roots, discovery, synthesized, Optional.of(runtime)));
    }

    private RouteSynthesisRecipeCandidate candidate(RouteSynthesisRecipeAssemblyInput input, RouteSynthesisHandler handler) {
        Optional<RouteSynthesisRuntimeMappingResolution> runtime = input.runtime();
        if (runtime.isPresent() && runtime.get().requestCandidate() != null) {
            return withApiBasePath(runtime.get().requestCandidate(), input.request().apiBasePath());
        }
        return staticCandidate(handler, input.request().apiBasePath());
    }

    private RouteSynthesisRecipeCandidate staticCandidate(
            RouteSynthesisHandler handler, String apiBasePath) {
        String path = joinPath(apiBasePath, handler.path());
        String body = switch (handler.httpMethod()) {
            case "POST", "PUT", "PATCH" -> "{}";
            default -> null;
        };
        return new RouteSynthesisRecipeCandidate(
                handler.httpMethod(), path, "", path, body,
                List.of("spring_mapping_annotation"), List.of(),
                List.of("spring_http_handler_annotation"));
    }

    private RouteSynthesisRecipeCandidate withApiBasePath(
            RouteSynthesisRecipeCandidate candidate, String apiBasePath) {
        String path = joinPath(apiBasePath, candidate.path());
        return new RouteSynthesisRecipeCandidate(candidate.method(), path, candidate.queryTemplate(),
                path, candidate.bodyTemplate(), candidate.assumptions(), candidate.needsConfirmation(),
                candidate.rationale());
    }

    private String joinPath(String base, String path) {
        String left = base == null ? "" : base.trim();
        String right = path == null ? "" : path.trim();
        String joined = (left + "/" + right).replaceAll("/{2,}", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private RouteSynthesisRuntimeCapture runtimeCapture(
            CreateRecipeRequest request, RouteSynthesisHandler handler) {
        Integer line = request.lineHint() == null ? handler.firstExecutableLine() : request.lineHint();
        if (runtimeEvidenceProvider == null || line == null || line < 1) {
            return RouteSynthesisRuntimeCapture.unavailable("probe_key_or_line_missing");
        }
        String methodKey = handler.runtimeClassFqcn() + "#" + handler.methodName();
        RouteSynthesisProbeRouteResolution route = probeRouteResolver.resolve(
                request.probeId(), request.probeBaseUrl());
        return runtimeEvidenceProvider.capture(methodKey, line, route);
    }

    private boolean invalidLineTarget(
            CreateRecipeRequest request, RouteSynthesisRuntimeCapture runtimeCapture) {
        return "line_probe".equals(request.intentMode())
                && "invalid_line_target".equals(runtimeCapture.reason());
    }

    private List<String> evidence(RouteSynthesisRecipeAssemblyInput input, RouteSynthesisRuntimeCapture runtimeCapture) {
        List<String> evidence = new ArrayList<>();
        input.runtime().ifPresent(runtime -> evidence.addAll(runtime.evidence()));
        evidence.add("handlerCount=" + input.discovery().handlers().size());
        evidence.add("runtimeCapture=" + runtimeCapture.status());
        return List.copyOf(evidence);
    }

    private List<String> strategies(RouteSynthesisRecipeAssemblyInput input) {
        List<String> strategies = new ArrayList<>();
        input.runtime().ifPresent(runtime -> strategies.addAll(runtime.attemptedStrategies()));
        strategies.addAll(List.of("target_inference_exact_match", "spring_http_handler_resolution",
                "registry_plugin_selection"));
        return List.copyOf(strategies);
    }

}
