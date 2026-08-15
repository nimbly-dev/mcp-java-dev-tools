package com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringHttpSynthesizerTest {

    @Test
    void exposesVersionedSpringHttpCompatibilityContract() {
        SpringHttpSynthesizer synthesizer = new SpringHttpSynthesizer();

        assertThat(synthesizer.name()).isEqualTo("spring_http");
        assertThat(synthesizer.apiVersion()).isEqualTo("1.0.0");
        assertThat(synthesizer.supportedFrameworks()).containsExactly("spring_http");
        assertThat(synthesizer.supportedIntents()).containsExactlyInAnyOrder("line_probe", "regression");
    }

    @Test
    void selectsFirstDeclarationOrderedHandlerMatchingMethodHint() {
        SpringHttpSynthesizer synthesizer = new SpringHttpSynthesizer();
        RouteSynthesisHandler first = handler("before");
        RouteSynthesisHandler selected = handler("run");
        RouteSynthesisHandlerDiscoveryResult discovery = RouteSynthesisHandlerDiscoveryResult.success(
                "example.WorkController", Path.of("WorkController.java"), List.of(first, selected), 1, List.of());

        RouteSynthesisSynthesisResult result = synthesizer.synthesize(
                request("run"), discovery);

        assertThat(result.compatible()).isTrue();
        assertThat(result.synthesizerUsed()).isEqualTo("spring_http");
        assertThat(result.selectedHandler()).isEqualTo(selected);
    }

    @Test
    void returnsDeterministicMissingCandidateFailure() {
        SpringHttpSynthesizer synthesizer = new SpringHttpSynthesizer();
        RouteSynthesisHandlerDiscoveryResult discovery = RouteSynthesisHandlerDiscoveryResult.success(
                "example.WorkController", Path.of("WorkController.java"), List.of(handler("run")), 1, List.of());

        RouteSynthesisSynthesisResult result = synthesizer.synthesize(
                request("missing"), discovery);

        assertThat(result.compatible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("request_candidate_missing");
        assertThat(result.failedStep()).isEqualTo("request_synthesis");
        assertThat(result.nextActionCode()).isEqualTo("refine_target_hints");
    }

    private CreateRecipeRequest request(String method) {
        return new CreateRecipeRequest(
                "project", List.of(), "example.WorkController", method, null, null,
                "static_only", null, "line_probe", null, null, null, null, null, null,
                null, null, null);
    }

    private RouteSynthesisHandler handler(String method) {
        return new RouteSynthesisHandler(
                "GET", "/work", method, "public String " + method + "()",
                "example.WorkController", 10, 14, 11, "validated", "runtime_probe_validation",
                null, "example.WorkController#" + method + ":11");
    }
}
