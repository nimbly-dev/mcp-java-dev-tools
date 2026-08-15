package com.nimbly.mcpjavadevtools.server.core.synthesis.registry;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.synthesis.api.Synthesizer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default registry for the built-in Synthesizer and v0.1.9 external-module policy.
 */
public class DefaultSynthesizerRegistry implements SynthesizerRegistry {

    private static final String SUPPORTED_API_VERSION = "1.0.0";

    private final List<Synthesizer> synthesizers;
    private final int externalModuleCount;

    /** Creates a deterministic registry from typed Application composition. */
    public DefaultSynthesizerRegistry(Synthesizer builtInSynthesizer, int externalModuleCount) {
        this(List.of(builtInSynthesizer), externalModuleCount);
    }

    /** Creates a deterministic registry from an ordered built-in set. */
    public DefaultSynthesizerRegistry(List<Synthesizer> synthesizers, int externalModuleCount) {
        this.synthesizers = copyAndValidate(synthesizers);
        this.externalModuleCount = Math.max(0, externalModuleCount);
    }

    /** Selects the built-in Synthesizer unless external modules require the blocker. */
    @Override
    public SynthesizerSelection select(CreateRecipeRequest request) {
        if (externalModuleCount > 0) {
            return new SynthesizerSelection(false, true, externalModuleCount, "synthesizer_not_installed");
        }
        Optional<Synthesizer> selected = compatibleSynthesizer(request);
        if (selected.isEmpty()) {
            return new SynthesizerSelection(false, false, 0, "synthesizer_not_installed");
        }
        return new SynthesizerSelection(true, false, 0, selected.get().name());
    }

    /** Delegates generation only after deterministic registry selection succeeds. */
    @Override
    public RouteSynthesisSynthesisResult synthesize(
            CreateRecipeRequest request,
            RouteSynthesisHandlerDiscoveryResult discovery) {
        Optional<Synthesizer> selected = compatibleSynthesizer(request);
        if (externalModuleCount > 0 || selected.isEmpty()) {
            return RouteSynthesisSynthesisResult.failure(
                    "synthesizer_not_installed", "plugin_selection", "synthesizer_not_installed");
        }
        return selected.get().synthesize(request, discovery);
    }

    private Optional<Synthesizer> compatibleSynthesizer(CreateRecipeRequest request) {
        return synthesizers.stream().filter(candidate -> supports(candidate, request)).findFirst();
    }

    private boolean supports(Synthesizer synthesizer, CreateRecipeRequest request) {
        String intent = request == null ? null : request.intentMode();
        Set<String> frameworks = synthesizer.supportedFrameworks();
        Set<String> intents = synthesizer.supportedIntents();
        boolean intentSupported = intent == null || intent.isBlank()
                || intents != null && intents.contains(intent);
        return hasText(synthesizer.name())
                && SUPPORTED_API_VERSION.equals(synthesizer.apiVersion())
                && frameworks != null && frameworks.contains("spring_http")
                && intentSupported;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<Synthesizer> copyAndValidate(List<Synthesizer> values) {
        Objects.requireNonNull(values, "synthesizers");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("synthesizers must not be empty");
        }
        Set<String> names = new HashSet<>();
        List<Synthesizer> copy = values.stream()
                .map(value -> Objects.requireNonNull(value, "synthesizer"))
                .toList();
        for (Synthesizer synthesizer : copy) {
            if (!hasText(synthesizer.name())) {
                throw new IllegalArgumentException("Synthesizer name must not be blank");
            }
            if (!SUPPORTED_API_VERSION.equals(synthesizer.apiVersion())) {
                throw new IllegalArgumentException(
                        "Synthesizer " + synthesizer.name()
                                + " must implement API version " + SUPPORTED_API_VERSION);
            }
            if (!names.add(synthesizer.name())) {
                throw new IllegalArgumentException("duplicate Synthesizer name: " + synthesizer.name());
            }
        }
        return copy;
    }
}
