package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, transport-neutral result envelope for Artifact Management. */
public record ArtifactManagementResult(
        String resultType,
        String status,
        String reasonCode,
        String nextActionCode,
        String nextAction,
        String reason,
        Map<String, Object> reasonMeta,
        Map<String, Object> details) {

    /** Normalizes result maps and never exposes mutable result state. */
    public ArtifactManagementResult {
        resultType = resultType == null ? "report" : resultType;
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "artifact_management_internal_error" : reasonCode;
        reasonMeta = copy(ArtifactRedactionPolicy.sanitizeMap(reasonMeta));
        details = copy(ArtifactRedactionPolicy.sanitizeMap(details));
    }

    /** Creates the compatibility success envelope. */
    public static ArtifactManagementResult success(
            ArtifactType artifactType,
            ArtifactAction action,
            Map<String, Object> details) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifactType", artifactType.value());
        output.put("action", action.value());
        if (details != null) {
            output.putAll(details);
        }
        return new ArtifactManagementResult(
                "artifact",
                "ok",
                "success",
                null,
                null,
                "",
                Map.of(),
                output);
    }

    /** Creates a deterministic Fail-Closed report. */
    public static ArtifactManagementResult blocked(
            String reasonCode,
            String reason,
            Map<String, Object> reasonMeta) {
        return new ArtifactManagementResult(
                "report",
                reasonCode,
                reasonCode,
                nextAction(reasonCode),
                reason,
                reason,
                reasonMeta,
                Map.of());
    }

    /** Creates the compatibility response for an absent Probe registry Artifact. */
    public static ArtifactManagementResult probeConfigNotConfigured(
            ArtifactType artifactType,
            ArtifactAction action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactType", artifactType.value());
        details.put("action", action.value());
        return new ArtifactManagementResult(
                "artifact",
                "not_configured",
                "probe_registry_not_configured",
                "set_probe_registry_config",
                "Place .mcpjvm/probe-config.json under the workspace (or a parent directory), then restart MCP server.",
                "",
                Map.of(),
                details);
    }

    private static String nextAction(String reasonCode) {
        if ("workspace_context_missing".equals(reasonCode)
                || "workspace_context_ambiguous".equals(reasonCode)) {
            return "bind_one MCP workspace root and retry";
        }
        if ("artifact_payload_required".equals(reasonCode)) {
            return "provide the required Artifact payload and retry";
        }
        return "correct the Artifact input or persisted state and retry";
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
