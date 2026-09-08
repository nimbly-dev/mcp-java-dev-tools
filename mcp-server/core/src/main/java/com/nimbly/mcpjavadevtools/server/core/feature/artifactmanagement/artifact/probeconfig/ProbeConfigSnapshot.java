package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Artifact-owned released Probe registry summary without expanding Probe runtime APIs. */
final class ProbeConfigSnapshot {

    private final ObjectMapper mapper;
    private final ProbeRegistryResolver resolver = new ProbeRegistryResolver();

    ProbeConfigSnapshot(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Loaded load(JsonNode artifact, Path configPath, Path workspaceRoot) {
        ProbeRegistry registry = resolver.resolve(artifact.toString(), true, workspaceRoot, List.of());
        Selection selection = selection(artifact, workspaceRoot);
        if (registry == null || selection == null) {
            throw new ArtifactOperationException(
                    "probe_config_invalid", "Probe registry configuration is invalid");
        }
        return new Loaded(registry, summary(selection, registry, configPath));
    }

    private Selection selection(JsonNode root, Path workspaceRoot) {
        if (root == null || !root.path("profiles").isObject()) {
            return null;
        }
        String profileName = root.path("defaultProfile").asText(null);
        String workspaceProfile = workspaceProfile(root.path("workspaces"), workspaceRoot);
        String source = "default";
        if (workspaceProfile != null) {
            profileName = workspaceProfile;
            source = "workspace";
        } else if (profileName == null || profileName.isBlank()) {
            Iterator<String> names = root.path("profiles").fieldNames();
            profileName = names.hasNext() ? names.next() : null;
        }
        JsonNode profile = profileName == null ? null : root.path("profiles").path(profileName);
        if (profile != null && profile.isObject() && profile.has("defaultProbe")) {
            throw new ArtifactOperationException("probe_config_invalid",
                    "profiles." + profileName + ".defaultProbe is not supported");
        }
        return profile != null && profile.isObject() ? new Selection(profileName, source, profile) : null;
    }

    private String workspaceProfile(JsonNode workspaces, Path workspaceRoot) {
        String selected = null;
        int selectedLength = -1;
        if (!workspaces.isArray() || workspaceRoot == null) {
            return null;
        }
        for (JsonNode workspace : workspaces) {
            String root = workspace.path("root").asText(null);
            String profile = workspace.path("profile").asText(null);
            Path candidate = path(root);
            if (candidate != null && profile != null
                    && (workspaceRoot.equals(candidate) || workspaceRoot.startsWith(candidate))
                    && candidate.toString().length() > selectedLength) {
                selected = profile.trim();
                selectedLength = candidate.toString().length();
            }
        }
        return selected;
    }

    private Map<String, Object> summary(
            Selection selection, ProbeRegistry registry, Path configPath) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("configFileAbs", configPath.toAbsolutePath().normalize().toString());
        details.put("activeProfile", selection.name());
        details.put("profileSource", selection.source());
        List<Map<String, Object>> probes = probes(selection.profile().path("probes"));
        if (probes.size() == 1) {
            details.put("implicitProbeId", probes.getFirst().get("id"));
        }
        details.put("probeCount", probes.size());
        details.put("allowNonWrappedExecutable", registry.allowNonWrappedExecutable());
        details.put("probes", probes);
        return details;
    }

    private List<Map<String, Object>> probes(JsonNode values) {
        List<Map<String, Object>> probes = new ArrayList<>();
        values.fields().forEachRemaining(entry -> probes.add(probe(entry.getKey(), entry.getValue())));
        return List.copyOf(probes);
    }

    private Map<String, Object> probe(String id, JsonNode value) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("id", id);
        probe.put("baseUrl", value.path("baseUrl").asText());
        String description = value.path("description").asText("").trim();
        if (!description.isEmpty()) {
            probe.put("description", description);
        }
        probe.put("include", strings(value.path("include")));
        probe.put("exclude", strings(value.path("exclude")));
        if (value.path("runtime").isObject()) {
            probe.put("runtime", mapper.convertValue(value.path("runtime"), Object.class));
        }
        return probe;
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> {
                String text = value.isTextual() ? value.asText().trim() : "";
                if (!text.isEmpty()) {
                    result.add(text);
                }
            });
        }
        return List.copyOf(result);
    }

    private Path path(String value) {
        try {
            return value == null ? null : Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    record Loaded(ProbeRegistry registry, Map<String, Object> summary) { }

    private record Selection(String name, String source, JsonNode profile) { }
}
