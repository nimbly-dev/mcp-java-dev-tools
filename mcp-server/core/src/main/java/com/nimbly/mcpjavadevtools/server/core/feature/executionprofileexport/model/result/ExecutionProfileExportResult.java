package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic transport-neutral result for Execution Profile Export. */
public record ExecutionProfileExportResult(
        String resultType,
        String status,
        String reasonCode,
        String nextActionCode,
        String nextAction,
        String reason,
        Map<String, Object> reasonMeta,
        Map<String, Object> details) {

    /** Normalizes result maps and keeps result state immutable. */
    public ExecutionProfileExportResult {
        resultType = resultType == null ? "report" : resultType;
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "execution_profile_export_internal_error" : reasonCode;
        reasonMeta = copy(reasonMeta);
        details = copy(details);
    }

    /** Creates the deterministic invalid-request result. */
    public static ExecutionProfileExportResult invalidRequest() {
        return new ExecutionProfileExportResult(
                "report", "execution_profile_export_request_invalid",
                "execution_profile_export_request_invalid", "correct_input",
                "Provide a valid execution profile export request.",
                "action and input are required", Map.of("failedStep", "input_validation"), Map.of());
    }

    /** Maps the approved Artifact boundary result without exposing its envelope fields. */
    public static ExecutionProfileExportResult fromArtifactResult(ArtifactManagementResult result) {
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        details.remove("artifactType");
        details.remove("action");
        String resultType = "ok".equals(result.status()) ? "execution_profile_export" : result.resultType();
        return new ExecutionProfileExportResult(
                resultType, result.status(), result.reasonCode(), result.nextActionCode(),
                result.nextAction(), result.reason(), result.reasonMeta(), details);
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
