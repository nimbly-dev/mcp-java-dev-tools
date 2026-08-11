package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class WorkspaceProbeRegistrySourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheActiveProfileFromTheCanonicalWorkspaceRegistry() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path registryDirectory = workspace.resolve(".mcpjvm");
        Files.createDirectories(registryDirectory);
        String workspaceRoot = workspace.toString().replace('\\', '/');
        Files.writeString(registryDirectory.resolve("probe-config.json"), """
                {
                  "defaultProfile": "dev",
                  "workspaces": [{"root": "%s", "profile": "ci"}],
                  "profiles": {
                    "dev": {"probes": {"orders": {"baseUrl": "http://dev.example"}}},
                    "ci": {"probes": {"orders": {"baseUrl": "http://ci.example"}}}
                  }
                }
                """.formatted(workspaceRoot));

        WorkspaceContext context = new WorkspaceContext(new DefaultApplicationArguments());
        context.refreshRoots(List.of(new McpSchema.Root(workspace.toUri().toString(), "workspace")));
        WorkspaceProbeRegistrySource provider = new WorkspaceProbeRegistrySource(
                context,
                new ProbeConfigurationProperties());

        ProbeRegistry registry = provider.current();

        assertThat(registry.findById("orders")).get().extracting("baseUrl").isEqualTo("http://ci.example");
    }

    @Test
    void usesTheBoundedPropertyRegistryWhenCanonicalRegistryIsUnavailable() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        WorkspaceContext context = new WorkspaceContext(new DefaultApplicationArguments());
        context.refreshRoots(List.of(new McpSchema.Root(workspace.toUri().toString(), "workspace")));

        ProbeConfigurationProperties.Registration registration = new ProbeConfigurationProperties.Registration();
        registration.setId("orders");
        registration.setBaseUrl("http://fallback.example");
        ProbeConfigurationProperties.Registry configured = new ProbeConfigurationProperties.Registry();
        configured.setRegistrations(List.of(registration));
        ProbeConfigurationProperties properties = new ProbeConfigurationProperties();
        properties.setRegistry(configured);

        WorkspaceProbeRegistrySource provider = new WorkspaceProbeRegistrySource(
                context,
                properties);

        assertThat(provider.current().findById("orders")).get().extracting("baseUrl")
                .isEqualTo("http://fallback.example");
    }

    @Test
    void failsClosedWhenCanonicalRegistryIsMalformed() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path registryDirectory = workspace.resolve(".mcpjvm");
        Files.createDirectories(registryDirectory);
        Files.writeString(registryDirectory.resolve("probe-config.json"), "{malformed");
        WorkspaceContext context = new WorkspaceContext(new DefaultApplicationArguments());
        context.refreshRoots(List.of(new McpSchema.Root(workspace.toUri().toString(), "workspace")));

        ProbeConfigurationProperties.Registration registration = new ProbeConfigurationProperties.Registration();
        registration.setId("orders");
        registration.setBaseUrl("http://fallback.example");
        ProbeConfigurationProperties.Registry configured = new ProbeConfigurationProperties.Registry();
        configured.setRegistrations(List.of(registration));
        ProbeConfigurationProperties properties = new ProbeConfigurationProperties();
        properties.setRegistry(configured);

        WorkspaceProbeRegistrySource provider = new WorkspaceProbeRegistrySource(context, properties);

        assertThat(provider.current()).isNull();
    }
}
