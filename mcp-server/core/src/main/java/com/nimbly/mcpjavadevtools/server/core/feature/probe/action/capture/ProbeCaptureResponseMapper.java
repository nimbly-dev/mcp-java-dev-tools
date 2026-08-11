package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRecord;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseTextCompactor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts one bounded Sidecar capture response into the typed Core outcome.
 */
class ProbeCaptureResponseMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeCaptureResponseMapper() {
    }

    static ProbeResult toResult(
            ProbeEndpointResponse response,
            String captureId,
            ProbeResponseCompactionPolicy compactionPolicy) {
        Optional<JsonNode> root = parseObject(response.payload());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return unavailable(root, response.statusCode(), compactionPolicy);
        }
        Optional<ProbeCaptureRecord> capture = capture(root, captureId, compactionPolicy);
        if (capture.isEmpty()) {
            return ProbeResult.withActionResult(
                    ProbeReasonCode.CAPTURE_FAILED,
                    ProbeReasonMetadata.status(),
                    new ProbeCaptureResult(false, null, "malformed_response"));
        }
        return ProbeResult.success(new ProbeCaptureResult(true, capture.get(), null));
    }

    private static Optional<JsonNode> parseObject(String payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static ProbeResult unavailable(
            Optional<JsonNode> root,
            int statusCode,
            ProbeResponseCompactionPolicy compactionPolicy) {
        JsonNode error = root.map(value -> value.path("error")).orElse(null);
        String reason = error != null && error.isTextual() ? error.asText() : "capture_unavailable";
        ProbeReasonCode reasonCode = statusCode == 404 && "capture_not_found".equals(reason)
                ? ProbeReasonCode.CAPTURE_NOT_FOUND
                : ProbeReasonCode.CAPTURE_FAILED;
        return ProbeResult.withActionResult(
                reasonCode,
                ProbeReasonMetadata.status(),
                new ProbeCaptureResult(false, null, ProbeResponseTextCompactor.compact(reason, compactionPolicy)));
    }

    private static Optional<ProbeCaptureRecord> capture(
            Optional<JsonNode> root,
            String captureId,
            ProbeResponseCompactionPolicy compactionPolicy) {
        if (root.isEmpty() || !root.get().path("capture").isObject()) {
            return Optional.empty();
        }
        JsonNode capture = root.get().path("capture");
        JsonNode responseCaptureId = capture.path("captureId");
        if (!responseCaptureId.isTextual() || !captureId.equals(responseCaptureId.asText())) {
            return Optional.empty();
        }
        List<String> executionPaths = new ArrayList<>();
        if (compactionPolicy.includeExecutionPaths() && capture.path("executionPaths").isArray()) {
            for (JsonNode path : capture.path("executionPaths")) {
                if (path.isTextual() && executionPaths.size() < compactionPolicy.maximumExecutionPaths()) {
                    executionPaths.add(ProbeResponseTextCompactor.compact(path.asText(), compactionPolicy));
                }
            }
        }
        return Optional.of(new ProbeCaptureRecord(
                captureId,
                capture.path("methodKey").isTextual()
                        ? ProbeResponseTextCompactor.compact(capture.path("methodKey").asText(), compactionPolicy)
                        : null,
                capture.path("capturedAtEpoch").canConvertToLong() ? capture.path("capturedAtEpoch").longValue() : null,
                capture.path("executionStartedAtEpoch").canConvertToLong()
                        ? capture.path("executionStartedAtEpoch").longValue() : null,
                capture.path("executionEndedAtEpoch").canConvertToLong()
                        ? capture.path("executionEndedAtEpoch").longValue() : null,
                capture.path("executionDurationMs").canConvertToLong()
                        ? capture.path("executionDurationMs").longValue() : null,
                capture.path("threadAllocatedBytesDelta").canConvertToLong()
                        ? capture.path("threadAllocatedBytesDelta").longValue() : null,
                capture.path("redactionMode").isTextual()
                        ? ProbeResponseTextCompactor.compact(capture.path("redactionMode").asText(), compactionPolicy)
                        : null,
                capture.path("args").isArray() ? capture.path("args").size() : null,
                capture.has("returnValue") && !capture.path("returnValue").isNull(),
                capture.has("thrownValue") && !capture.path("thrownValue").isNull(),
                capture.path("truncatedAny").isBoolean() ? capture.path("truncatedAny").booleanValue() : null,
                List.copyOf(executionPaths)));
    }
}
