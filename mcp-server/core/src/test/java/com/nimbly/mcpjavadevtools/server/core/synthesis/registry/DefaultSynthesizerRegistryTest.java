package com.nimbly.mcpjavadevtools.server.core.synthesis.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp.SpringHttpSynthesizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSynthesizerRegistryTest {

    @Test
    void selectsBuiltInSpringHttpSynthesizerDeterministically() {
        DefaultSynthesizerRegistry registry = new DefaultSynthesizerRegistry(
                new SpringHttpSynthesizer(), 0);

        SynthesizerSelection selection = registry.select(request());

        assertThat(selection.compatible()).isTrue();
        assertThat(selection.externalModulesConfigured()).isFalse();
        assertThat(selection.configuredModuleCount()).isZero();
        assertThat(selection.reasonCode()).isEqualTo("spring_http");
    }

    @Test
    void blocksExternalModulesWithOnlySanitizedEvidence() {
        DefaultSynthesizerRegistry registry = new DefaultSynthesizerRegistry(
                new SpringHttpSynthesizer(), 3);

        SynthesizerSelection selection = registry.select(request());

        assertThat(selection.compatible()).isFalse();
        assertThat(selection.externalModulesConfigured()).isTrue();
        assertThat(selection.configuredModuleCount()).isEqualTo(3);
        assertThat(selection.reasonCode()).isEqualTo("synthesizer_not_installed");
    }

    @Test
    void rejectsSynthesizerWithIncompatibleApiVersion() {
        SpringHttpSynthesizer incompatible = new SpringHttpSynthesizer() {
            @Override
            public String apiVersion() {
                return "1";
            }
        };

        assertThatThrownBy(() -> new DefaultSynthesizerRegistry(List.of(incompatible), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0.0");
    }

    private CreateRecipeRequest request() {
        return new CreateRecipeRequest(
                "project", List.of(), "example.WorkController", "run", null, null,
                "static_only", null, "line_probe", null, null, null, null, null, null,
                null, null, null);
    }
}
