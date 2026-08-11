package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeClassResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeSingleResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the compatibility fields documented for the TypeScript Probe Tool.
 */
public final class ProbeDeterministicOutputMapper {

    public Map<String, Object> map(ProbeRequest request, ProbeResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (request != null) {
            Map<String, Object> requestDetails = new LinkedHashMap<>();
            if (request.targetSelector() != null) {
                requestDetails.put("probeId", request.targetSelector().probeId());
                requestDetails.put("baseUrl", request.targetSelector().baseUrl());
            }
            switch (request) {
                case ProbeCheckRequest check -> addCheckRequestDetails(requestDetails, check);
                case ProbeSingleStatusRequest single -> addSingleStatusRequestDetails(requestDetails, single);
                case ProbeBatchStatusRequest batch -> addBatchStatusRequestDetails(requestDetails, batch);
                case ProbeSingleResetRequest single -> addSingleResetRequestDetails(requestDetails, single);
                case ProbeBatchResetRequest batch -> addBatchResetRequestDetails(requestDetails, batch);
                case ProbeClassResetRequest classReset -> addClassResetRequestDetails(requestDetails, classReset);
                case ProbeWaitForHitRequest wait -> addWaitRequestDetails(requestDetails, wait);
                case ProbeCaptureRequest capture -> addCaptureRequestDetails(requestDetails, capture);
                case ProbeActuateRequest actuate -> addActuateRequestDetails(requestDetails, actuate);
                case ProbeProfilerRequest profiler -> addProfilerRequestDetails(requestDetails, profiler);
                default -> { }
            }
            details.put("request", requestDetails);
        }
        if (result != null && result.actionResult().isPresent()) {
            Object actionResult = result.actionResult().orElseThrow();
            switch (actionResult) {
                case ProbeCheckResult check -> addCheckDetails(details, request, check);
                case ProbeStatusResult status -> {
                    if (request instanceof ProbeBatchStatusRequest) {
                        addBatchStatusDetails(details, status);
                    } else {
                        addSingleStatusDetails(details, status);
                    }
                }
                case ProbeResetResult reset -> addResetDetails(details, request, reset);
                case ProbeCaptureResult capture -> addCaptureDetails(details, capture);
                case ProbeActuateResult actuate -> addActuateDetails(details, actuate);
                case ProbeProfilerResult profiler -> addProfilerDetails(details, profiler);
                case ProbeWaitForHitResult wait -> addWaitDetails(details, wait);
                default -> { }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    private static void addCheckRequestDetails(
            Map<String, Object> details,
            ProbeCheckRequest request) {
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
        details.put("authConfigured", !request.headers().isEmpty());
        details.put("authHeaderNames", List.copyOf(request.headers().keySet()));
    }

    private static void addSingleStatusRequestDetails(
            Map<String, Object> details,
            ProbeSingleStatusRequest request) {
        if (request.keySelector() != null) {
            details.put("key", request.keySelector().key());
            details.put("lineHint", request.keySelector().lineHint());
        }
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addBatchStatusRequestDetails(
            Map<String, Object> details,
            ProbeBatchStatusRequest request) {
        details.put("keys", request.keySelector().keys());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addSingleResetRequestDetails(
            Map<String, Object> details,
            ProbeSingleResetRequest request) {
        if (request.keySelector() != null) {
            details.put("key", request.keySelector().key());
            details.put("lineHint", request.keySelector().lineHint());
        }
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addBatchResetRequestDetails(
            Map<String, Object> details,
            ProbeBatchResetRequest request) {
        details.put("keys", request.keySelector().keys());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addClassResetRequestDetails(
            Map<String, Object> details,
            ProbeClassResetRequest request) {
        details.put("className", request.className());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addWaitRequestDetails(
            Map<String, Object> details,
            ProbeWaitForHitRequest request) {
        if (request.keySelector() != null) {
            details.put("key", request.keySelector().key());
            details.put("lineHint", request.keySelector().lineHint());
        }
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
        details.put("pollIntervalMs", request.pollInterval() == null ? null : request.pollInterval().toMillis());
        details.put("maxRetries", request.maxRetries());
    }

    private static void addCaptureRequestDetails(
            Map<String, Object> details,
            ProbeCaptureRequest request) {
        details.put("captureId", request.captureId());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addActuateRequestDetails(
            Map<String, Object> details,
            ProbeActuateRequest request) {
        details.put("action", request.command() == null ? null : request.command().value());
        details.put("sessionId", request.sessionId());
        details.put("actuatorId", request.actuatorId());
        details.put("targetKey", request.targetKey());
        details.put("returnBoolean", request.returnBoolean());
        details.put("ttlMs", request.ttlMs());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addProfilerRequestDetails(
            Map<String, Object> details,
            ProbeProfilerRequest request) {
        details.put("action", request.command() == null ? null : request.command().value());
        details.put("sessionId", request.sessionId());
        details.put("provider", request.provider() == null ? null : request.provider().value());
        details.put("event", request.event());
        details.put("intervalNanos", request.intervalNanos());
        details.put("outputPath", request.outputPath());
        details.put("outputFormat", request.outputFormat());
        details.put("timeoutMs", request.timeout() == null ? null : request.timeout().toMillis());
    }

    private static void addCheckDetails(
            Map<String, Object> details,
            ProbeRequest request,
            ProbeCheckResult check) {
        Map<String, Object> config = new LinkedHashMap<>();
        boolean authConfigured = request instanceof ProbeCheckRequest checkRequest
                && !checkRequest.headers().isEmpty();
        config.put("authConfigured", authConfigured);
        config.put("authHeaderNames", request instanceof ProbeCheckRequest checkRequest
                ? List.copyOf(checkRequest.headers().keySet())
                : List.of());
        details.put("config", config);
        Map<String, Object> checks = new LinkedHashMap<>();
        ProbeCheckEndpointResult reset = check.reset();
        Map<String, Object> resetDetails = new LinkedHashMap<>();
        resetDetails.put("ok", reset.available());
        resetDetails.put("status", reset.httpStatus());
        resetDetails.put("keyDecodingOk", reset.responseKey() != null);
        checks.put("reset", resetDetails);
        ProbeCheckEndpointResult status = check.status();
        Map<String, Object> statusDetails = new LinkedHashMap<>();
        statusDetails.put("ok", status.available());
        statusDetails.put("status", status.httpStatus());
        statusDetails.put("keyDecodingOk", status.responseKey() != null);
        checks.put("status", statusDetails);
        details.put("checks", checks);
        details.put("recommendations", check.recommendations());
    }

    private static void addBatchStatusDetails(
            Map<String, Object> details,
            ProbeStatusResult status) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProbeStatusEntry entry : status.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", entry.key() == null ? entry.requestedKey() : entry.key());
            row.put("executionHit", entry.lineHit() ? "line_hit" : "not_hit");
            row.put("apiOutcome", Boolean.TRUE.equals(entry.endpointOk()) ? "ok" : "error");
            row.put("reproStatus", entry.lineValidation() == null ? "status_checked" : entry.lineValidation());
            row.put("hitCount", entry.hitCount());
            row.put("lastHitEpoch", entry.lastHitEpoch());
            row.put("reasonCode", entry.lineValidation());
            rows.add(row);
        }
        long ok = rows.stream().filter(row -> "ok".equals(row.get("apiOutcome"))).count();
        details.put("mode", "probe_batch");
        details.put("operation", "status");
        details.put("summary", Map.of("total", rows.size(), "ok", ok, "failed", rows.size() - ok));
        details.put("results", rows);
    }

    private static void addSingleStatusDetails(
            Map<String, Object> details,
            ProbeStatusResult status) {
        ProbeStatusEntry entry = status.entries().isEmpty() ? null : status.entries().getFirst();
        if (entry == null) {
            return;
        }
        details.put("targetKey", entry.key());
        details.put("executionHit", entry.lineHit() ? "line_hit" : "not_hit");
        details.put("apiOutcome", Boolean.TRUE.equals(entry.endpointOk()) ? "ok" : "error");
        details.put("reproStatus", entry.lineValidation() == null ? "status_checked" : entry.lineValidation());
        long hitCount = entry.hitCount() == null ? 0 : entry.hitCount();
        long lastHit = entry.lastHitEpoch() == null ? 0 : entry.lastHitEpoch();
        details.put("probeHit", "hitCount=" + hitCount + ", lastHitEpoch=" + lastHit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", entry.httpStatus());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", entry.key());
        json.put("hitCount", entry.hitCount());
        json.put("lastHitEpoch", entry.lastHitEpoch());
        json.put("lineResolvable", entry.lineResolvable());
        json.put("lineValidation", entry.lineValidation());
        json.put("capturePreview", entry.capturePreview());
        json.put("runtime", entry.runtime());
        response.put("json", json);
        details.put("response", response);
        if (entry.runtime() != null) {
            details.put("runtime", entry.runtime());
        }
    }

    private static void addResetDetails(Map<String, Object> details, ProbeRequest request, ProbeResetResult reset) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProbeResetEntry entry : reset.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", entry.key());
            row.put("reset", entry.reset());
            row.put("apiOutcome", Boolean.TRUE.equals(entry.reset()) ? "ok" : "error");
            row.put("httpStatus", entry.httpStatus());
            row.put("lineResolvable", entry.lineResolvable());
            row.put("lineValidation", entry.lineValidation());
            rows.add(row);
        }
        if (request instanceof ProbeBatchResetRequest || request instanceof ProbeClassResetRequest) {
            long ok = rows.stream().filter(row -> "ok".equals(row.get("apiOutcome"))).count();
            details.put("mode", "probe_batch");
            details.put("operation", "reset");
            details.put("summary", Map.of("total", rows.size(), "ok", ok, "failed", rows.size() - ok));
            details.put("results", rows);
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", rows.isEmpty() ? null : rows.getFirst().get("httpStatus"));
        details.put("response", response);
    }

    private static void addCaptureDetails(
            Map<String, Object> details,
            ProbeCaptureResult capture) {
        if (capture.capture() != null) {
            details.put("targetKey", capture.capture().methodKey());
        }
    }

    private static void addActuateDetails(Map<String, Object> details, ProbeActuateResult actuate) {
        details.put("mode", actuate.mode());
        details.put("targetKey", actuate.targetKey());
        details.put("apiOutcome", actuate.actuated() ? "ok" : "error");
        details.put("reproStatus", actuate.scopeState());
        Map<String, Object> responseJson = new LinkedHashMap<>();
        responseJson.put("action", actuate.command());
        responseJson.put("sessionId", actuate.sessionId());
        responseJson.put("scopeState", actuate.scopeState());
        responseJson.put("expiresAtEpoch", actuate.expiresAtEpoch());
        details.put("response", Map.of("json", responseJson));
    }

    private static void addProfilerDetails(Map<String, Object> details, ProbeProfilerResult profiler) {
        details.put("apiOutcome", Boolean.TRUE.equals(profiler.supported()) ? "ok" : "error");
        details.put("reproStatus", profiler.status());
        Map<String, Object> responseJson = new LinkedHashMap<>();
        responseJson.put("action", profiler.command());
        responseJson.put("status", profiler.status());
        responseJson.put("provider", profiler.provider());
        responseJson.put("sessionId", profiler.sessionId());
        responseJson.put("outputPath", profiler.outputPath());
        responseJson.put("downloadedBytes", profiler.downloadedBytes());
        details.put("response", Map.of("json", responseJson));
    }

    private static void addWaitDetails(Map<String, Object> details, ProbeWaitForHitResult wait) {
        details.put("targetKey", wait.key());
        details.put("executionHit", wait.outcome() == ProbeWaitOutcome.LINE_HIT ? "line_hit" : "not_hit");
        details.put("apiOutcome", wait.outcome() == ProbeWaitOutcome.UNREACHABLE ? "error" : "ok");
        details.put("reproStatus", wait.outcome().name().toLowerCase());
        details.put("probeHit", wait.observedHitCount() == null
                ? "No trusted status evidence"
                : "hitCount=" + wait.observedHitCount());
        if (wait.lastStatus() != null && wait.lastStatus().runtime() != null) {
            details.put("runtime", wait.lastStatus().runtime());
        }
    }
}
