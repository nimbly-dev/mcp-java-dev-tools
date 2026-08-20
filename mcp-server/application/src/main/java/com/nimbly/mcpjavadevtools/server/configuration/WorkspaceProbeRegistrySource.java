package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistryInput;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryReloader;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryResolver;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads external workspace/configuration input for the Core Probe registry resolver.
 */
@Component
public final class WorkspaceProbeRegistrySource implements ProbeRegistryProvider, ProbeRegistryReloader {

    private static final String REGISTRY_DIRECTORY = ".mcpjvm";
    private static final String REGISTRY_FILE = "probe-config.json";

    private final WorkspaceContext workspaceContext;
    private final ProbeConfigurationProperties properties;
    private final ProbeRegistryResolver resolver;
    private volatile ProbeRegistry activeRegistry;
    private volatile Path activeWorkspaceRoot;

    /**
     * Creates the external registry source.
     *
     * @param workspaceContext current MCP workspace context
     * @param properties application-bound fallback configuration
     */
    public WorkspaceProbeRegistrySource(
            WorkspaceContext workspaceContext,
            ProbeConfigurationProperties properties) {
        this.workspaceContext = workspaceContext;
        this.properties = properties;
        this.resolver = new ProbeRegistryResolver();
    }

    /**
     * Reads the current canonical file and delegates all registry rules to Core.
     *
     * @return current registry or fail-closed absence
     */
    @Override
    public ProbeRegistry current() {
        WorkspaceSnapshot snapshot = workspaceContext.snapshot();
        Path workspaceRoot = snapshot == null ? null : snapshot.root();
        if (activeRegistry == null || !java.util.Objects.equals(activeWorkspaceRoot, workspaceRoot)) {
            return reload();
        }
        return activeRegistry;
    }

    /** Re-reads the canonical source and atomically replaces the active registry. */
    @Override
    public synchronized ProbeRegistry reload() {
        WorkspaceSnapshot snapshot = workspaceContext.snapshot();
        Path workspaceRoot = snapshot == null ? null : snapshot.root();
        Path canonicalPath = canonicalPath(workspaceRoot);
        boolean canonicalPresent = canonicalPath != null && Files.isRegularFile(canonicalPath);
        String canonicalJson = canonicalPresent ? read(canonicalPath) : null;
        ProbeRegistry resolved = resolver.resolve(canonicalJson, canonicalPresent, workspaceRoot, fallback());
        activeWorkspaceRoot = workspaceRoot;
        activeRegistry = resolved;
        return resolved;
    }

    private static Path canonicalPath(Path workspaceRoot) {
        return workspaceRoot == null
                ? null
                : workspaceRoot.resolve(REGISTRY_DIRECTORY).resolve(REGISTRY_FILE);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private List<ProbeRegistryInput> fallback() {
        List<ProbeRegistryInput> values = new ArrayList<>();
        if (properties.getRegistry() == null || properties.getRegistry().getRegistrations() == null) {
            return values;
        }
        for (ProbeConfigurationProperties.Registration registration
                : properties.getRegistry().getRegistrations()) {
            values.add(new ProbeRegistryInput(registration.getId(), registration.getBaseUrl()));
        }
        return values;
    }
}
