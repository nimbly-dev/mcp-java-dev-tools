package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryReloader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Purpose-owned operations for the Probe configuration Artifact family. */
public final class ProbeConfigOperations {
    private final ArtifactManagementSupport support;
    private final ProbeRegistryReloader reloader;
    private final ProbeConfigSnapshot snapshot;
    private volatile Map<String, Object> activeSummary;

    /** Creates the Probe configuration owner from filesystem lifecycle behavior. */
    public ProbeConfigOperations(ArtifactManagementSupport support) {
        this(support, null);
    }

    /** Creates the Probe owner with the live registry lifecycle port. */
    public ProbeConfigOperations(ArtifactManagementSupport support, ProbeRegistryReloader reloader) {
        this.support = support;
        this.reloader = reloader;
        this.snapshot = new ProbeConfigSnapshot(support.mapper());
    }

    /** Reads the Probe configuration Artifact. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            if (!Files.isRegularFile(path)) {
                if (activeSummary != null) {
                    return support.success(request, new LinkedHashMap<>(activeSummary));
                }
                return ArtifactManagementResult.probeConfigNotConfigured(request.artifactType(), request.action());
            }
            try {
                JsonNode artifact = support.jsonStore().read(path);
                Map<String, Object> details = load(artifact, path, workspace.root()).summary();
                if (artifact != null && !artifact.isNull()) {
                    details.put("artifact", support.mapper().convertValue(artifact, Object.class));
                }
                return support.success(request, details);
            } catch (ArtifactOperationException ignored) {
                if (activeSummary != null) {
                    return support.success(request, new LinkedHashMap<>(activeSummary));
                }
                throw ignored;
            }
        });
    }

    /** Validates the Probe configuration Artifact. */
    public ArtifactManagementResult validate(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            if (!Files.isRegularFile(path)) {
                return ArtifactManagementResult.probeConfigNotConfigured(request.artifactType(), request.action());
            }
            JsonNode artifact = support.jsonStore().read(path);
            return support.success(request, load(artifact, path, workspace.root()).summary());
        });
    }

    /** Upserts the Probe configuration Artifact. */
    public ArtifactManagementResult upsert(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            JsonNode payload = support.payload(request);
            ArtifactManagementSupport.requireObject(
                    payload, "probe_config_invalid", "probe configuration must be a JSON object");
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            support.jsonStore().write(path, payload);
            ProbeConfigSnapshot.Loaded persisted = load(payload, path, workspace.root());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("path", workspace.paths().relative(path));
            details.put("reloadApplied", reloader != null);
            if (reloader != null) {
                ProbeRegistry active = reloader.reload();
                ProbeRegistry resolved = active == null ? persisted.registry() : active;
                details.putAll(persisted.summary());
                details.put("activeProbeCount", resolved.size());
            } else {
                details.putAll(persisted.summary());
            }
            return support.success(request, details);
        });
    }

    /** Reloads the Probe configuration Artifact. */
    public ArtifactManagementResult reload(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            if (!Files.isRegularFile(path)) {
                return ArtifactManagementResult.probeConfigNotConfigured(request.artifactType(), request.action());
            }
            JsonNode artifact = support.jsonStore().read(path);
            ProbeConfigSnapshot.Loaded persisted = load(artifact, path, workspace.root());
            ProbeRegistry active = reloader == null ? null : reloader.reload();
            if (active == null) {
                active = persisted.registry();
            }
            Map<String, Object> details = persisted.summary();
            details.put("reloadApplied", reloader != null);
            details.put("activeProbeCount", active.size());
            return support.success(request, "reloaded", details);
        });
    }

    private ProbeConfigSnapshot.Loaded load(JsonNode artifact, Path path, Path workspaceRoot) {
        ProbeConfigSnapshot.Loaded loaded = snapshot.load(artifact, path, workspaceRoot);
        activeSummary = Map.copyOf(loaded.summary());
        return new ProbeConfigSnapshot.Loaded(
                loaded.registry(), new LinkedHashMap<>(loaded.summary()));
    }
}
