package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmMutationResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Core lifecycle results to the existing TypeScript-compatible envelope.
 */
public final class JvmLifecycleMcpResponseMapper
        implements McpActionResponseMapper<JvmLifecycleRequest, JvmLifecycleResult> {

    @Override
    public McpActionResponse map(JvmLifecycleRequest request, JvmLifecycleResult result) {
        return result.actionResult().map(action -> mapAction(result, action))
                .orElseGet(() -> report(result.reasonCode(), result.status().value(), Map.of()));
    }

    /** Creates the stable invalid-request response. */
    public McpActionResponse invalidRequest() {
        return report(
                "jvm_lifecycle_request_invalid",
                "blocked",
                Map.of("failedStep", "input_validation"));
    }

    /** Maps an unexpected Application boundary error without leaking details. */
    public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report",
                "internal_error",
                "internal_error",
                "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of("correlationId", failure.correlationId(),
                        "failedStep", failure.failureKind().value()),
                null,
                Map.of());
    }

    private static McpActionResponse mapAction(
            JvmLifecycleResult result,
            JvmLifecycleActionResult action) {
        if (action instanceof JvmListResult list) {
            return new McpActionResponse(
                    "jvm_list", result.status().value(), result.reasonCode(),
                    null, null, "", Map.of(), null, Map.of("jvms", candidates(list.jvms())));
        }
        if (action instanceof JvmMutationResult mutation) {
            return mutationResponse(result, mutation);
        }
        return report(result.reasonCode(), result.status().value(), Map.of());
    }

    private static McpActionResponse mutationResponse(
            JvmLifecycleResult result,
            JvmMutationResult mutation) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("selectedJvm", Map.of(
                "pid", mutation.pid(),
                "expectedProcessStartEpochMs", mutation.expectedProcessStartEpochMs()));
        details.put("lifecycle", Map.of(
                "operation", mutation.operation(), "outcome", mutation.outcome()));
        if (mutation.probeHost() != null && mutation.probePort() != null) {
            details.put("probe", Map.of(
                    "baseUrl", "http://" + mutation.probeHost() + ":" + mutation.probePort(),
                    "verification", "pending"));
        } else {
            details.put("nonRestorableClasses", mutation.nonRestorableClasses());
        }
        return new McpActionResponse(
                "jvm_lifecycle", result.status().value(), result.reasonCode(),
                null, null, "", Map.of(), null, details);
    }

    private static List<Map<String, Object>> candidates(List<JvmCandidate> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JvmCandidate candidate : values) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("pid", candidate.pid());
            mapped.put("identityHint", candidate.identityHint());
            mapped.put("identitySource", candidate.identitySource());
            mapped.put("frameworkHint", candidate.frameworkHint());
            mapped.put("frameworkEvidence", candidate.frameworkEvidence());
            mapped.put("processStartEpochMs", candidate.processStartEpochMs());
            mapped.put("attachmentState", "unverified");
            mapped.put("probeState", "unverified");
            result.add(mapped);
        }
        return result;
    }

    private static McpActionResponse report(
            String reasonCode,
            String status,
            Map<String, Object> reasonMeta) {
        return new McpActionResponse(
                "report", status, reasonCode, null, null, "", reasonMeta, null, Map.of());
    }
}
