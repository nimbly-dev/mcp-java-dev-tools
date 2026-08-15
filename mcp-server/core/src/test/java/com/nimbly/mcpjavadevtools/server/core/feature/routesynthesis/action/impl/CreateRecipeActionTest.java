package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.DefaultRouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeCollaborators;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template.DefaultRouteSynthesisRecipeTemplateRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.SynthesizerRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CreateRecipeActionTest {

    @TempDir
    Path tempDirectory;

    @Test
    void returnsFrozenExternalPluginBlockerWithoutLeakingConfiguration() {
        CreateRecipeAction action = new CreateRecipeAction(request -> new SynthesizerSelection(
                false, true, 2, "synthesizer_not_installed"));

        RouteSynthesisResult result = action.execute(request());

        assertThat(result.status()).isEqualTo("report");
        assertThat(result.reasonCode()).isEqualTo("synthesizer_not_installed");
        assertThat(result.failedStep()).isEqualTo("plugin_bootstrap");
        assertThat(result.attemptedStrategies()).containsExactly("registry_plugin_bootstrap");
        assertThat(result.evidence()).containsExactly("configuredModuleCount=2");
        assertThat(result.evidence()).noneMatch(value -> value.contains("secret"));
    }

    @Test
    void routesRuntimeMappingsAndTemplateThroughOwnedCollaborators() throws Exception {
        Path project = tempDirectory.resolve("project");
        Files.createDirectories(project);
        AtomicReference<String> mappingsUrl = new AtomicReference<>();
        RouteSynthesisRecipeCandidate runtimeCandidate = new RouteSynthesisRecipeCandidate(
                "GET", "/runtime/run", "", "/runtime/run", null,
                List.of("runtime_mapping"), List.of(), List.of("spring_runtime_actuator_mappings"));
        RouteSynthesisHandler handler = new RouteSynthesisHandler(
                "GET", "/runtime/run", "run", "runtime", "example.WorkController",
                10, 12, 11, "unavailable", "runtime_mapping", null, null);
        RouteSynthesisHandlerDiscoveryResult discovery = RouteSynthesisHandlerDiscoveryResult.success(
                "example.WorkController", project.resolve("WorkController.java"), List.of(handler), 1, List.of());
        SynthesizerRegistry registry = new SynthesizerRegistry() {
            @Override
            public SynthesizerSelection select(CreateRecipeRequest request) {
                return new SynthesizerSelection(true, false, 0, null);
            }

            @Override
            public RouteSynthesisSynthesisResult synthesize(
                    CreateRecipeRequest request, RouteSynthesisHandlerDiscoveryResult ignored) {
                return RouteSynthesisSynthesisResult.success("spring_http", handler);
            }
        };
        CreateRecipeAction action = new CreateRecipeAction(
                () -> Optional.of(new RouteSynthesisWorkspaceSnapshot(tempDirectory)),
                (root, roots, hint) -> discovery,
                new DefaultRouteSynthesisAuthenticationResolver(), registry,
                new CreateRecipeCollaborators(
                        (url, classHint, methodHint, authToken) -> {
                            mappingsUrl.set(url);
                            return RouteSynthesisRuntimeMappingResolution.success(
                                    runtimeCandidate, List.of("runtime_mapping=ok"),
                                    List.of("spring_runtime_actuator_mappings"));
                        },
                        null,
                        new DefaultRouteSynthesisRecipeTemplateRenderer(),
                        (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution
                                .resolved("http://probe.example")));

        RouteSynthesisResult result = action.execute(new CreateRecipeRequest(
                "project", List.of(), "example.WorkController", "run", null,
                "http://runtime.example/actuator/mappings", "runtime_only", null, "regression",
                null, null, null, null, null, null, "{{http.method}} {{http.path}}", null, null));

        assertThat(mappingsUrl).hasValue("http://runtime.example/actuator/mappings");
        assertThat(result.resultType()).isEqualTo("recipe");
        RouteSynthesisRecipeResult recipe = (RouteSynthesisRecipeResult) result.actionResult();
        assertThat(recipe.requestCandidates()).extracting(RouteSynthesisRecipeCandidate::path)
                .containsExactly("/runtime/run");
        assertThat(recipe.rendered()).isEqualTo("GET /runtime/run");
    }

    private CreateRecipeRequest request() {
        return new CreateRecipeRequest(
                "project",
                List.of(),
                "example.Work",
                "run",
                null,
                null,
                "static_only",
                null,
                "line_probe",
                "secret-token",
                "user",
                "secret-password",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}


