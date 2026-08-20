package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryReloader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Purpose-owned Probe configuration Artifact boundary used by Probe actions. */
public final class ProbeConfigArtifacts {
    private final ArtifactManagementSupport support;
    private final ProbeRegistryReloader reloader;

    /** Creates the Probe configuration owner from filesystem lifecycle behavior. */
    public ProbeConfigArtifacts(ArtifactManagementSupport support) {
        this(support, null);
    }

    /** Creates the Probe owner with the live registry lifecycle port. */
    public ProbeConfigArtifacts(ArtifactManagementSupport support, ProbeRegistryReloader reloader) {
        this.support = support;
        this.reloader = reloader;
    }

    /** Reads the Probe configuration Artifact. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            if (!Files.isRegularFile(path)) {
                return ArtifactManagementResult.probeConfigNotConfigured(request.artifactType(), request.action());
            }
            JsonNode artifact = support.readOrNotConfigured(path);
            return support.success(request, Map.of("artifact", artifact));
        });
    }

    /** Validates the Probe configuration Artifact. */
    public ArtifactManagementResult validate(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            Path path = workspace.paths().resolve(".mcpjvm", "probe-config.json");
            if (!Files.isRegularFile(path)) {
                return ArtifactManagementResult.probeConfigNotConfigured(request.artifactType(), request.action());
            }
            JsonNode artifact = support.readOrNotConfigured(path);
            ArtifactManagementSupport.requireObject(
                    artifact, "probe_config_invalid", "probe configuration must be a JSON object");
            return support.success(request, Map.of("valid", true));
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
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("path", workspace.paths().relative(path));
            details.put("reloadApplied", reloader != null);
            if (reloader != null) {
                ProbeRegistry active = reloader.reload();
                details.put("activeProbeCount", active == null ? 0 : active.size());
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
            JsonNode artifact = support.readOrNotConfigured(path);
            ArtifactManagementSupport.requireObject(
                    artifact, "probe_config_invalid", "probe configuration must be a JSON object");
            if (reloader == null) {
                throw new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException(
                        "probe_registry_reload_unavailable", "active Probe registry reload is unavailable");
            }
            ProbeRegistry active = reloader.reload();
            int probeCount = active == null ? 0 : active.size();
            return support.success(request, Map.of("valid", true, "reloadApplied", true,
                    "activeProbeCount", probeCount));
        });
    }
}
