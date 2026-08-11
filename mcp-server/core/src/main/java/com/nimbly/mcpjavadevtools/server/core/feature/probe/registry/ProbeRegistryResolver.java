package com.nimbly.mcpjavadevtools.server.core.feature.probe.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistryInput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Applies the canonical Probe registry profile and workspace-selection rules.
 *
 * <p>The resolver performs no filesystem I/O. Hosts provide canonical JSON
 * content and the workspace root, while Core owns parsing and selection policy.</p>
 */
public final class ProbeRegistryResolver {

    private final ObjectMapper objectMapper;

    /** Creates a resolver with the Core-owned JSON parser. */
    public ProbeRegistryResolver() {
        objectMapper = JsonMapper.builder().build();
    }

    /**
     * Resolves canonical content when present, otherwise the supplied property fallback.
     * A present but invalid canonical source fails closed and never falls back.
     *
     * @param canonicalJson canonical registry content, when readable
     * @param canonicalPresent whether the canonical source exists
     * @param workspaceRoot active workspace root
     * @param fallback property-backed raw inputs used only when source is absent
     * @return resolved registry, or {@code null} when no valid registry exists
     */
    public ProbeRegistry resolve(
            String canonicalJson,
            boolean canonicalPresent,
            Path workspaceRoot,
            Collection<ProbeRegistryInput> fallback) {
        if (canonicalPresent) {
            return readCanonical(canonicalJson, workspaceRoot);
        }
        return fallback(fallback);
    }

    private ProbeRegistry readCanonical(String canonicalJson, Path workspaceRoot) {
        try {
            JsonNode root = objectMapper.readTree(canonicalJson);
            JsonNode profile = activeProfile(root, workspaceRoot);
            return profile == null ? null : registrations(profile);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private JsonNode activeProfile(JsonNode root, Path workspaceRoot) {
        if (root == null || !root.path("profiles").isObject()) {
            return null;
        }
        String profileName = root.path("defaultProfile").asText(null);
        String workspaceProfile = workspaceProfile(root.path("workspaces"), workspaceRoot);
        if (workspaceProfile != null) {
            profileName = workspaceProfile;
        }
        if (profileName == null || profileName.isBlank()) {
            Iterator<String> names = root.path("profiles").fieldNames();
            profileName = names.hasNext() ? names.next() : null;
        }
        return profileName == null ? null : root.path("profiles").path(profileName);
    }

    private String workspaceProfile(JsonNode workspaces, Path workspaceRoot) {
        if (!workspaces.isArray() || workspaceRoot == null) {
            return null;
        }
        String selected = null;
        int selectedLength = -1;
        for (JsonNode workspace : workspaces) {
            String configuredRoot = workspace.path("root").asText(null);
            String profile = workspace.path("profile").asText(null);
            if (configuredRoot == null || profile == null || !matches(workspaceRoot, configuredRoot)) {
                continue;
            }
            int length = Path.of(configuredRoot).toAbsolutePath().normalize().toString().length();
            if (length > selectedLength) {
                selected = profile.trim();
                selectedLength = length;
            }
        }
        return selected;
    }

    private boolean matches(Path workspaceRoot, String configuredRoot) {
        try {
            Path candidate = Path.of(configuredRoot).toAbsolutePath().normalize();
            return workspaceRoot.equals(candidate) || workspaceRoot.startsWith(candidate);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ProbeRegistry registrations(JsonNode profile) {
        JsonNode probes = profile.path("probes");
        if (!probes.isObject()) {
            return null;
        }
        List<ProbeRegistration> values = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = probes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String baseUrl = field.getValue().path("baseUrl").asText(null);
            if (baseUrl == null || baseUrl.isBlank()) {
                return null;
            }
            values.add(new ProbeRegistration(field.getKey(), baseUrl));
        }
        return values.isEmpty() ? null : new ProbeRegistry(values);
    }

    private ProbeRegistry fallback(Collection<ProbeRegistryInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        List<ProbeRegistration> registrations = new ArrayList<>();
        for (ProbeRegistryInput input : inputs) {
            registrations.add(new ProbeRegistration(input.id(), input.baseUrl()));
        }
        return registrations.isEmpty()
                ? null
                : new ProbeRegistry(registrations);
    }
}
