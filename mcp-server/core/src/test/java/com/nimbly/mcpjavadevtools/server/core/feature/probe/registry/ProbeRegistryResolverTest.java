package com.nimbly.mcpjavadevtools.server.core.feature.probe.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistryInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeRegistryResolverTest {

    private final ProbeRegistryResolver resolver = new ProbeRegistryResolver();

    @Test
    void selectsTheMostSpecificMatchingWorkspaceProfile() {
        Path workspace = Path.of("C:/workspace/project").toAbsolutePath().normalize();
        String root = workspace.toString().replace('\\', '/');
        String json = """
                {
                  "defaultProfile": "dev",
                  "workspaces": [
                    {"root": "%s", "profile": "ci"}
                  ],
                  "profiles": {
                    "dev": {"probes": {"orders": {"baseUrl": "http://dev.example"}}},
                    "ci": {
                      "global": {"allowNonWrappedExecutable": true},
                      "probes": {"orders": {"baseUrl": "http://ci.example"}}
                    }
                  }
                }
                """.formatted(root);

        ProbeRegistry registry = resolver.resolve(json, true, workspace, List.of());

        assertThat(registry.findById("orders")).get().extracting("baseUrl")
                .isEqualTo("http://ci.example");
        assertThat(registry.allowNonWrappedExecutable()).isTrue();
    }

    @Test
    void failsClosedForMalformedCanonicalContentInsteadOfUsingFallback() {
        ProbeRegistry registry = resolver.resolve(
                "{malformed",
                true,
                Path.of("C:/workspace"),
                List.of(new ProbeRegistryInput("fallback", "http://fallback.example")));

        assertThat(registry).isNull();
    }

    @Test
    void usesFallbackOnlyWhenCanonicalContentIsAbsent() {
        ProbeRegistry registry = resolver.resolve(
                null,
                false,
                null,
                List.of(new ProbeRegistryInput("fallback", "http://fallback.example")));

        assertThat(registry.findById("fallback")).isPresent();
        assertThat(registry.allowNonWrappedExecutable()).isFalse();
    }

    @Test
    void defaultsCanonicalWrapperPolicyToFalseWhenGlobalSectionIsAbsent() {
        ProbeRegistry registry = resolver.resolve(
                "{\"profiles\":{\"dev\":{\"probes\":{\"live\":{\"baseUrl\":\"http://probe.example\"}}}}}",
                true,
                Path.of("C:/workspace"),
                List.of());

        assertThat(registry.allowNonWrappedExecutable()).isFalse();
    }

}
