package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses and bounds the existing helper JSON protocol.
 */
public final class JvmLifecycleHelperProtocol {

    private static final int MAX_ITEMS = 128;
    private static final int MAX_TEXT = 256;

    private JvmLifecycleHelperProtocol() {
    }

    /** Parses one bounded helper response. */
    public static Optional<JvmLifecycleHelperResult> parse(String text, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(text);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            String operation = required(root, "operation");
            String outcome = required(root, "outcome");
            String reasonCode = requiredReason(root, "reasonCode");
            List<String> pids = stringArray(root.get("pids"), MAX_ITEMS, true);
            List<JvmCandidate> candidates = candidates(root.get("candidates"));
            List<String> classes = stringArray(root.get("nonRestorableClasses"), MAX_ITEMS, false);
            if (pids == null || candidates == null || classes == null) {
                return Optional.empty();
            }
            return Optional.of(new JvmLifecycleHelperResult(
                    operation, outcome, reasonCode, pids, candidates, classes));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static List<JvmCandidate> candidates(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > MAX_ITEMS) {
            return null;
        }
        List<JvmCandidate> result = new ArrayList<>();
        for (JsonNode candidate : value) {
            JvmCandidate parsed = candidate(candidate);
            if (parsed == null) {
                return null;
            }
            result.add(parsed);
        }
        return result;
    }

    private static JvmCandidate candidate(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        String pid = text(value.get("pid"));
        String source = text(value.get("identitySource"));
        String framework = text(value.get("frameworkHint"));
        List<String> evidence = evidence(value.get("frameworkEvidence"));
        Long start = number(value.get("processStartEpochMs"));
        JsonNode identity = value.get("identityHint");
        String identityHint = identity == null || identity.isNull() ? null : text(identity);
        if (pid == null || !pid.matches("[1-9][0-9]*") || pid.length() > MAX_TEXT
                || !validSource(source) || !validFramework(framework) || evidence == null
                || startInvalid(value.get("processStartEpochMs"), start)
                || identityHintInvalid(identityHint)) {
            return null;
        }
        return new JvmCandidate(pid, identityHint, source, framework, evidence, start);
    }

    private static boolean startInvalid(JsonNode node, Long value) {
        return node != null && !node.isNull() && (value == null || value <= 0L);
    }

    private static boolean identityHintInvalid(String value) {
        return value != null && value.length() > 128;
    }

    private static boolean validSource(String value) {
        return "sanitized_attach_descriptor".equals(value)
                || "sanitized_executable_basename".equals(value)
                || "unavailable".equals(value);
    }

    private static boolean validFramework(String value) {
        return "spring_boot_candidate".equals(value) || "unknown".equals(value);
    }

    private static List<String> evidence(JsonNode value) {
        List<String> result = stringArray(value, 4, false);
        if (result == null || result.stream().anyMatch(entry ->
                !"spring_boot_launcher".equals(entry)
                        && !"executable_jar_name".equals(entry))) {
            return null;
        }
        return result;
    }

    private static List<String> stringArray(JsonNode value, int max, boolean numeric) {
        if (value == null || !value.isArray() || value.size() > max) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode entry : value) {
            String parsed = text(entry);
            if (parsed == null || parsed.length() > MAX_TEXT
                    || numeric && !parsed.matches("[1-9][0-9]*")) {
                return null;
            }
            result.add(parsed);
        }
        return result;
    }

    private static String required(JsonNode root, String field) {
        String value = text(root.get(field));
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new IllegalArgumentException("helper field is invalid");
        }
        return value;
    }

    private static String requiredReason(JsonNode root, String field) {
        String value = required(root, field);
        if (!value.matches("[a-z0-9_]{1,128}")) {
            throw new IllegalArgumentException("helper reason code is invalid");
        }
        return value;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Long number(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }
}
