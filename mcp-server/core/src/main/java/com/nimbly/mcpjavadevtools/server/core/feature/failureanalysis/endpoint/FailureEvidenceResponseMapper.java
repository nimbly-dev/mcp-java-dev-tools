package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalysisEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerificationEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureExceptionSection;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFrame;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.redaction.FailureAnalysisRedactor;
import java.util.ArrayList;
import java.util.List;

/** Parses only known bounded Sidecar fields into sanitized Feature values. */
public final class FailureEvidenceResponseMapper {

    private final FailureAnalysisPolicy policy;

    public FailureEvidenceResponseMapper(FailureAnalysisPolicy policy) {
        this.policy = policy;
    }

    /** @param payload untrusted JSON payload @return sanitized analyze evidence */
    public FailureAnalysisEvidence analyze(JsonNode payload) {
        JsonNode fingerprint = object(payload, "fingerprint");
        return new FailureAnalysisEvidence(
                fingerprint(fingerprint),
                frames(payload, "investigationCandidates"),
                frame(object(payload, "dependencyBoundary")),
                sections(payload),
                strings(payload, "reasons", policy.maximumSections()));
    }

    /** @param payload untrusted JSON payload @return sanitized verify evidence */
    public FailureVerificationEvidence verify(JsonNode payload) {
        return new FailureVerificationEvidence(
                text(payload, "outcome"),
                fingerprint(object(payload, "observedFingerprint")),
                strings(payload, "reasons", policy.maximumSections()));
    }

    private FailureFingerprint fingerprint(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        FailureFrame nearest = frame(object(value, "nearestApplicationFrame"));
        String methodKey = text(value, "nearestApplicationMethodKey");
        if (methodKey == null && nearest != null) {
            methodKey = nearest.methodKey();
        }
        String exceptionType = text(value, "exceptionType");
        String rootCauseType = text(value, "rootCauseType");
        boolean complete = value.path("complete").asBoolean(false)
                && exceptionType != null && rootCauseType != null && methodKey != null;
        List<String> incomplete = strings(value, "incompletenessReasons", policy.maximumSections());
        if (!complete && incomplete.isEmpty()) {
            incomplete = List.of("fingerprint_fields_missing");
        }
        return new FailureFingerprint(
                exceptionType,
                rootCauseType,
                methodKey,
                nearest,
                text(value, "normalizedMessage"),
                complete,
                incomplete);
    }

    private List<FailureFrame> frames(JsonNode parent, String field) {
        List<FailureFrame> values = new ArrayList<>();
        JsonNode array = parent == null ? null : parent.get(field);
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode value : array) {
            if (values.size() >= policy.maximumFrames()) {
                break;
            }
            FailureFrame parsed = frame(value);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return List.copyOf(values);
    }

    private List<FailureExceptionSection> sections(JsonNode payload) {
        List<FailureExceptionSection> values = new ArrayList<>();
        JsonNode array = payload == null ? null : payload.get("exceptionSections");
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode value : array) {
            if (values.size() >= policy.maximumSections() || !value.isObject()) {
                break;
            }
            values.add(new FailureExceptionSection(
                    text(value, "exceptionType"),
                    value.path("suppressed").asBoolean(false),
                    value.path("elidedFrames").asBoolean(false),
                    frames(value, "frames")));
        }
        return List.copyOf(values);
    }

    private FailureFrame frame(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        Integer line = value.has("lineNumber") && value.path("lineNumber").isInt()
                ? value.path("lineNumber").asInt() : null;
        if (line != null && line <= 0) {
            line = null;
        }
        return new FailureFrame(
                text(value, "className"), text(value, "methodName"),
                FailureAnalysisRedactor.path(text(value, "sourceFile"), policy.maximumStringLength()), line,
                text(value, "ownership"), FailureAnalysisRedactor.path(
                        text(value, "codeSource"), policy.maximumStringLength()), text(value, "methodDescriptor"),
                paths(value, "codeSourceCandidates", 8), text(value, "resolutionReason"));
    }

    private List<String> strings(JsonNode parent, String field, int limit) {
        List<String> values = new ArrayList<>();
        JsonNode array = parent == null ? null : parent.get(field);
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode value : array) {
            if (values.size() >= limit || !value.isTextual()) {
                break;
            }
            String text = FailureAnalysisRedactor.text(value.asText(), policy.maximumStringLength());
            if (text != null) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private List<String> paths(JsonNode parent, String field, int limit) {
        List<String> values = new ArrayList<>();
        JsonNode array = parent == null ? null : parent.get(field);
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode value : array) {
            if (values.size() >= limit || !value.isTextual()) {
                break;
            }
            String path = FailureAnalysisRedactor.path(value.asText(), policy.maximumStringLength());
            if (path != null) {
                values.add(path);
            }
        }
        return List.copyOf(values);
    }

    private String text(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || !parent.path(field).isTextual()) {
            return null;
        }
        return FailureAnalysisRedactor.text(parent.path(field).asText(), policy.maximumStringLength());
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isObject() ? value : null;
    }
}
