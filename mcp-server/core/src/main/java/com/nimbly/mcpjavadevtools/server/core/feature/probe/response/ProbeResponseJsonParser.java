package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeCapturePreview;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeRuntimeHints;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeRuntimePortHint;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeStatusPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses Sidecar JSON through an allowlisted, bounded Probe response model.
 */
public class ProbeResponseJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProbeResponseJsonParser() {
    }

    /**
     * Parses one status envelope or legacy direct status payload.
     *
     * @param payload bounded endpoint response body
     * @param policy response compaction policy
     * @return compact status payload when structurally valid
     */
    public static Optional<ProbeStatusPayload> parseStatus(
            String payload,
            ProbeResponseCompactionPolicy policy) {
        Optional<JsonNode> root = parseObject(payload);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        JsonNode probe = objectChild(root.get(), "probe");
        return Optional.of(statusPayload(probe == null ? root.get() : probe, null, root.get(), policy));
    }

    /**
     * Parses a batch status envelope while retaining only structurally valid rows.
     *
     * @param payload bounded endpoint response body
     * @param policy response compaction policy
     * @return compact batch rows in Sidecar response order
     */
    public static Optional<List<ProbeStatusPayload>> parseStatusBatch(
            String payload,
            ProbeResponseCompactionPolicy policy) {
        Optional<JsonNode> root = parseObject(payload);
        if (root.isEmpty() || !root.get().path("results").isArray()) {
            return Optional.empty();
        }
        List<ProbeStatusPayload> rows = new ArrayList<>();
        for (JsonNode row : root.get().path("results")) {
            addBatchRow(rows, row, policy);
        }
        return Optional.of(List.copyOf(rows));
    }

    private static void addBatchRow(
            List<ProbeStatusPayload> rows,
            JsonNode row,
            ProbeResponseCompactionPolicy policy) {
        JsonNode probe = objectChild(row, "probe");
        if (probe == null) {
            return;
        }
        rows.add(statusPayload(probe, optionalBoolean(row, "ok"), row, policy));
    }

    private static ProbeStatusPayload statusPayload(
            JsonNode probe,
            Boolean endpointOk,
            JsonNode envelope,
            ProbeResponseCompactionPolicy policy) {
        return new ProbeStatusPayload(
                endpointOk,
                boundedString(probe, "key", policy),
                optionalLong(probe, "hitCount"),
                optionalLong(probe, "lastHitEpoch"),
                optionalBoolean(probe, "lineResolvable"),
                boundedString(probe, "lineValidation", policy),
                capturePreview(objectChild(envelope, "capturePreview"), policy),
                runtimeHints(objectChild(envelope, "runtime"), policy));
    }

    private static ProbeCapturePreview capturePreview(
            JsonNode preview,
            ProbeResponseCompactionPolicy policy) {
        if (preview == null) {
            return null;
        }
        return new ProbeCapturePreview(
                optionalBoolean(preview, "available"),
                boundedString(preview, "captureId", policy),
                optionalLong(preview, "capturedAtEpoch"),
                optionalLong(preview, "executionStartedAtEpoch"),
                optionalLong(preview, "executionEndedAtEpoch"),
                optionalLong(preview, "executionDurationMs"),
                optionalLong(preview, "threadAllocatedBytesDelta"),
                boundedString(preview, "methodKey", policy),
                boundedString(preview, "redactionMode", policy),
                optionalBoolean(preview, "truncatedAny"),
                executionPaths(preview.path("executionPaths"), policy));
    }

    private static ProbeRuntimeHints runtimeHints(
            JsonNode runtime,
            ProbeResponseCompactionPolicy policy) {
        if (runtime == null) {
            return null;
        }
        return new ProbeRuntimeHints(
                boundedString(runtime, "mode", policy),
                boundedString(runtime, "sessionId", policy),
                boundedString(runtime, "runtimeInstanceId", policy),
                boundedString(runtime, "actuatorId", policy),
                boundedString(runtime, "actuateTargetKey", policy),
                optionalBoolean(runtime, "actuateReturnBoolean"),
                optionalLong(runtime, "expiresAtEpoch"),
                boundedString(runtime, "scopeState", policy),
                optionalInteger(runtime, "activeSessionCount"),
                portHint(objectChild(runtime, "appPort"), policy));
    }

    private static ProbeRuntimePortHint portHint(
            JsonNode appPort,
            ProbeResponseCompactionPolicy policy) {
        if (appPort == null) {
            return null;
        }
        return new ProbeRuntimePortHint(
                optionalInteger(appPort, "value"),
                boundedString(appPort, "source", policy));
    }

    private static List<String> executionPaths(JsonNode paths, ProbeResponseCompactionPolicy policy) {
        if (!policy.includeExecutionPaths() || !paths.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode path : paths) {
            addBoundedPath(values, path, policy);
        }
        return List.copyOf(values);
    }

    private static void addBoundedPath(
            List<String> values,
            JsonNode path,
            ProbeResponseCompactionPolicy policy) {
        if (!path.isTextual() || values.size() == policy.maximumExecutionPaths()) {
            return;
        }
        values.add(bound(path.asText(), policy));
    }

    private static Optional<JsonNode> parseObject(String payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static JsonNode objectChild(JsonNode node, String fieldName) {
        JsonNode child = node.path(fieldName);
        return child.isObject() ? child : null;
    }

    private static String boundedString(
            JsonNode node,
            String fieldName,
            ProbeResponseCompactionPolicy policy) {
        JsonNode field = node.path(fieldName);
        return field.isTextual() ? bound(field.asText(), policy) : null;
    }

    private static String bound(String value, ProbeResponseCompactionPolicy policy) {
        if (value.length() <= policy.maximumStringLength()) {
            return value;
        }
        return value.substring(0, policy.maximumStringLength());
    }

    private static Boolean optionalBoolean(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isBoolean() ? field.booleanValue() : null;
    }

    private static Long optionalLong(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.canConvertToLong() ? field.longValue() : null;
    }

    private static Integer optionalInteger(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.canConvertToInt() ? field.intValue() : null;
    }
}
