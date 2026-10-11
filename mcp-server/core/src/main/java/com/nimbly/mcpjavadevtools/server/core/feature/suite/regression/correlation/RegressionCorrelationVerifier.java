package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionStepExpectationEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns deterministic per-plan correlation matching and fail-closed diagnostics. */
public class RegressionCorrelationVerifier {

    private final RegressionCorrelationRuntime runtime;
    /** Creates the verifier from its runtime evidence boundary. */
    public RegressionCorrelationVerifier(RegressionCorrelationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    /** Opens a runtime lease before trigger execution when correlation is enabled. */
    public Execution open(JsonNode contract, String executionId) {
        JsonNode correlation = contract.path("correlation");
        if (!correlation.path("enabled").asBoolean(false)) {
            return new Execution();
        }
        return new Execution(correlation, runtime.open(correlation, executionId));
    }

    /** Active correlation execution spanning trigger and runtime-evidence phases. */
    public final class Execution implements AutoCloseable {

        private final JsonNode correlation;
        private final RegressionCorrelationRuntime.Session session;
        private final boolean enabled;

        private Execution(JsonNode correlation, RegressionCorrelationRuntime.Session session) {
            this.correlation = correlation;
            this.session = session;
            this.enabled = true;
        }

        private Execution() {
            this.correlation = null;
            this.session = null;
            this.enabled = false;
        }

        /** Matches plan and runtime events within configured scope and time budgets. */
        public Outcome verify(
                JsonNode contract,
                Map<Integer, Map<String, Object>> outputs,
                Map<Integer, Long> stepStartedAt) {
            if (!enabled) {
                return Outcome.notConfigured();
            }
            Key key = resolveKey(correlation.path("key"), outputs);
            if (key.value() == null) {
                return outcome("fail_closed", "missing_correlation_key", key, List.of(), Map.of());
            }
            List<Map<String, Object>> events = syntheticEvents(contract, outputs, stepStartedAt, key);
            JsonNode runtimeEvidence = correlation.path("runtimeEvidence");
            boolean runtimeRequired = runtimeEvidence.path("required").asBoolean(false);
            long durationMs = bounded(runtimeEvidence.path("maxDurationMs").asLong(30_000L), 1_000L, 60_000L);
            long deadline = System.currentTimeMillis() + durationMs;
            Match match;
            do {
                mergeRuntimeEvents(events, session.readEvents());
                match = match(events, key, runtimeRequired);
                if (match.success() || !runtimeRequired || System.currentTimeMillis() >= deadline) {
                    break;
                }
                pause(Math.min(100L, Math.max(1L, deadline - System.currentTimeMillis())));
            } while (true);
            return outcome(
                    match.success() ? "ok" : "fail_closed",
                    match.reasonCode(),
                    key,
                    match.timeline(),
                    match.reasonMeta());
        }

        private Match match(List<Map<String, Object>> events, Key key, boolean runtimeRequired) {
            String fingerprint = fingerprint(key.value());
            RuntimeScope runtimeScope = runtimeScope(events);
            if (runtimeRequired && runtimeScope.scopedEvents().isEmpty()) {
                String reason = runtimeScope.allEvents().isEmpty()
                        ? "missing_expected_flow_event"
                        : "correlation_probe_scope_mismatch";
                return Match.failure(reason, List.of(), flowMeta(List.of()));
            }
            List<Map<String, Object>> matching = events.stream()
                    .filter(event -> matchesKey(event, key, fingerprint))
                    .filter(event -> !"runtime_line_hit".equals(event.get("eventType"))
                            || !runtimeScope.configured() && !runtimeRequired
                            || runtimeScope.scopedEvents().contains(event))
                    .sorted(eventComparator(correlation))
                    .toList();
            if (runtimeRequired && matching.stream()
                    .noneMatch(event -> "runtime_line_hit".equals(event.get("eventType")))) {
                return Match.failure("correlation_key_not_observed", List.of(), flowMeta(List.of()));
            }
            if (ambiguousRuntime(matching)) {
                return Match.failure("ambiguous_correlation", matching, flowMeta(matching));
            }
            if (matching.isEmpty()) {
                return Match.failure("no_matching_events", List.of(), flowMeta(List.of()));
            }
            long first = number(matching.get(0).get("timestampEpochMs"));
            long last = number(matching.get(matching.size() - 1).get("timestampEpochMs"));
            long maxWindowMs = correlation.path("window").path("maxWindowMs").asLong();
            if (last - first > maxWindowMs) {
                return Match.failure("window_exceeded", matching, flowMeta(matching));
            }
            if (!correlation.path("crossPlan").asBoolean(false)) {
                List<String> expectedFlow = strings(correlation.path("expectedFlow"));
                int missingAt = firstMissingFlowIndex(matching, expectedFlow);
                if (missingAt >= 0) {
                    return Match.failure("missing_expected_flow_event", matching, flowMeta(matching));
                }
            }
            return Match.success(matching, flowMeta(matching));
        }

        private RuntimeScope runtimeScope(List<Map<String, Object>> events) {
            JsonNode configuration = correlation.path("runtimeEvidence");
            List<String> probeIds = strings(configuration.path("probeIds"));
            List<String> instanceIds = strings(configuration.path("runtimeInstanceIds"));
            List<Map<String, Object>> runtimeEvents = events.stream()
                    .filter(event -> "runtime_line_hit".equals(event.get("eventType")))
                    .toList();
            List<Map<String, Object>> scopedEvents = runtimeEvents.stream()
                    .filter(event -> probeIds.isEmpty()
                            || probeIds.contains(String.valueOf(event.get("probeId"))))
                    .filter(event -> instanceIds.isEmpty()
                            || instanceIds.contains(String.valueOf(event.get("runtimeInstanceId"))))
                    .toList();
            return new RuntimeScope(!probeIds.isEmpty() || !instanceIds.isEmpty(), runtimeEvents, scopedEvents);
        }

        private boolean ambiguousRuntime(List<Map<String, Object>> events) {
            Set<String> runtimeIdentities = new LinkedHashSet<>();
            for (Map<String, Object> event : events) {
                if ("runtime_line_hit".equals(event.get("eventType"))) {
                    Object value = event.get("runtimeInstanceId");
                    String identity = value instanceof String text && !text.isBlank()
                            ? text.trim() : null;
                    if (identity != null) {
                        runtimeIdentities.add(identity);
                    }
                }
            }
            return runtimeIdentities.size() > 1;
        }

        private record RuntimeScope(
                boolean configured,
                List<Map<String, Object>> allEvents,
                List<Map<String, Object>> scopedEvents) {
        }

        private List<Map<String, Object>> syntheticEvents(
                JsonNode contract,
                Map<Integer, Map<String, Object>> outputs,
                Map<Integer, Long> stepStartedAt,
                Key key) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (JsonNode step : contract.path("steps")) {
                int order = step.path("order").asInt();
                Map<String, Object> output = outputs.get(order);
                if (output == null || !"pass".equals(output.get("status"))) {
                    continue;
                }
                String probeId = targetProbeId(contract.path("targets"), step.path("targetRef").asInt(0));
                if (probeId == null) {
                    List<String> probeIds = strings(correlation.path("probeIds"));
                    probeId = probeIds.size() == 1 ? probeIds.get(0) : null;
                }
                if (probeId == null) {
                    continue;
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventId", step.path("id").asText() + ":" + order);
                event.put("probeId", probeId);
                event.put("timestampEpochMs", stepStartedAt.getOrDefault(order, System.currentTimeMillis()));
                event.put("keyType", key.type());
                event.put("keyValue", key.value());
                event.put("eventType", step.path("protocol").asText());
                events.add(Map.copyOf(event));
            }
            return events;
        }

        private void mergeRuntimeEvents(
                List<Map<String, Object>> events,
                List<Map<String, Object>> observed) {
            Set<String> identities = new LinkedHashSet<>();
            for (Map<String, Object> event : events) {
                identities.add(identity(event));
            }
            for (Map<String, Object> event : observed) {
                if (identities.add(identity(event))) {
                    events.add(Map.copyOf(event));
                }
            }
        }

        private String identity(Map<String, Object> event) {
            return String.valueOf(event.get("probeId")) + ":"
                    + String.valueOf(event.get("runtimeInstanceId")) + ":"
                    + String.valueOf(event.get("sequence")) + ":"
                    + String.valueOf(event.get("eventId"));
        }

        private Map<String, Object> flowMeta(List<Map<String, Object>> timeline) {
            List<String> expected = strings(correlation.path("expectedFlow"));
            List<String> observed = timeline.stream()
                    .map(event -> String.valueOf(event.get("probeId")))
                    .toList();
            List<String> missing = expected.stream()
                    .filter(probeId -> !observed.contains(probeId))
                    .distinct()
                    .toList();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("expectedFlow", expected);
            meta.put("observedProbeIds", observed);
            meta.put("missingProbeIds", missing);
            int missingAt = firstMissingFlowIndex(timeline, expected);
            if (missingAt >= 0) {
                meta.put("firstUnsatisfiedFlowIndex", missingAt);
            }
            return Map.copyOf(meta);
        }

        private int firstMissingFlowIndex(List<Map<String, Object>> timeline, List<String> expected) {
            int timelineIndex = 0;
            for (int flowIndex = 0; flowIndex < expected.size(); flowIndex++) {
                while (timelineIndex < timeline.size()
                        && !expected.get(flowIndex).equals(timeline.get(timelineIndex).get("probeId"))) {
                    timelineIndex++;
                }
                if (timelineIndex >= timeline.size()) {
                    return flowIndex;
                }
                timelineIndex++;
            }
            return -1;
        }

        private Outcome outcome(
                String status,
                String reasonCode,
                Key key,
                List<Map<String, Object>> timeline,
                Map<String, Object> reasonMeta) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("status", status);
            artifact.put("reasonCode", reasonCode);
            artifact.put("crossPlan", correlation.path("crossPlan").asBoolean(false));
            artifact.put("correlationSessionId", correlation.path("correlationSessionId").asText());
            artifact.put("keyType", key.type());
            if (key.value() != null) {
                artifact.put("keyValue", key.value());
            }
            artifact.put("window", Map.of("maxWindowMs",
                    correlation.path("window").path("maxWindowMs").asLong()));
            artifact.put("expectedFlow", strings(correlation.path("expectedFlow")));
            artifact.put("timeline", List.copyOf(timeline));
            artifact.put("generatedAtEpochMs", Instant.now().toEpochMilli());
            if (!reasonMeta.isEmpty()) {
                artifact.put("reasonMeta", reasonMeta);
            }
            return new Outcome(true, "ok".equals(status), reasonCode, Map.copyOf(artifact));
        }

        @Override
        public void close() {
            if (session != null) {
                session.close();
            }
        }
    }

    private Key resolveKey(JsonNode key, Map<Integer, Map<String, Object>> outputs) {
        String type = key.path("type").asText("messageId");
        String explicit = key.path("value").asText().trim();
        if (!explicit.isEmpty()) {
            return new Key(type, explicit);
        }
        JsonNode source = key.path("source");
        String path = source.path("path").asText();
        int sourceOrder = source.path("stepOrder").asInt(0);
        if (sourceOrder > 0) {
            return new Key(type, value(outputs.get(sourceOrder), path));
        }
        for (Map<String, Object> output : outputs.values()) {
            String value = value(output, path);
            if (value != null) {
                return new Key(type, value);
            }
        }
        return new Key(type, null);
    }

    private String value(Map<String, Object> output, String path) {
        if (output == null || path.isBlank()) {
            return null;
        }
        Object value = RegressionStepExpectationEvaluator.read(output, path);
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean matchesKey(Map<String, Object> event, Key key, String fingerprint) {
        if (!key.type().equals(event.get("keyType"))) {
            return false;
        }
        Object value = event.get("keyValue");
        return value != null && key.value().equals(String.valueOf(value).trim())
                || fingerprint.equals(event.get("keyFingerprint"));
    }

    private Comparator<Map<String, Object>> eventComparator(JsonNode correlation) {
        List<String> flow = strings(correlation.path("expectedFlow"));
        return Comparator.comparingLong((Map<String, Object> event) -> number(event.get("timestampEpochMs")))
                .thenComparingInt(event -> {
                    int index = flow.indexOf(String.valueOf(event.get("probeId")));
                    return index < 0 ? Integer.MAX_VALUE : index;
                })
                .thenComparing(event -> String.valueOf(event.get("eventId")));
    }

    private static String targetProbeId(JsonNode targets, int targetRef) {
        if (!targets.isArray() || targetRef < 0 || targetRef >= targets.size()) {
            return null;
        }
        String value = targets.get(targetRef).path("runtimeVerification").path("probeId").asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static List<String> strings(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) {
                strings.add(value.asText().trim());
            }
        }
        return List.copyOf(strings);
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RegressionCorrelationRuntimeException("correlation_runtime_interrupted");
        }
    }

    private record Key(String type, String value) {
    }

    private record Match(
            boolean success,
            String reasonCode,
            List<Map<String, Object>> timeline,
            Map<String, Object> reasonMeta) {

        private static Match success(List<Map<String, Object>> timeline, Map<String, Object> reasonMeta) {
            return new Match(true, "ok", List.copyOf(timeline), reasonMeta);
        }

        private static Match failure(
                String reasonCode,
                List<Map<String, Object>> timeline,
                Map<String, Object> reasonMeta) {
            return new Match(false, reasonCode, List.copyOf(timeline), reasonMeta);
        }
    }

    /** Verification outcome and persistable artifact. */
    public record Outcome(boolean configured, boolean success, String reasonCode, Map<String, Object> artifact) {

        private static Outcome notConfigured() {
            return new Outcome(false, true, "ok", Map.of());
        }
    }
}
