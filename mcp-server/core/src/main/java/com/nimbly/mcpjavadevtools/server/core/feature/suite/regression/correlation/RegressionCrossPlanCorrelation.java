package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Aggregates persisted per-plan correlation artifacts for one execution profile. */
public class RegressionCrossPlanCorrelation {

    private static final Pattern TOKEN_SAFE_SESSION = Pattern.compile("[A-Za-z0-9_.-]+");

    /** Promotes an unambiguous correlation key for use by a later plan. */
    public void promote(Map<String, Object> suiteContext, String planName, Map<String, Object> outcome) {
        Map<String, Object> artifact = correlation(outcome);
        if (!crossPlan(artifact)) {
            return;
        }
        Session session = new Session(artifact);
        session.add(planName, artifact);
        String keyValue = session.keyValue();
        if (keyValue == null) {
            return;
        }
        putContext(suiteContext, "suite.correlation.last", session, planName, keyValue);
        if (TOKEN_SAFE_SESSION.matcher(session.id).matches()) {
            putContext(suiteContext, "suite.correlation." + session.id, session, planName, keyValue);
        }
    }

    /** Returns deterministic summaries for sessions contributed to by at least two plans. */
    public List<Map<String, Object>> summarize(List<Map<String, Object>> outcomes) {
        Map<String, Session> sessions = new LinkedHashMap<>();
        for (Map<String, Object> outcome : outcomes) {
            Map<String, Object> artifact = correlation(outcome);
            if (!crossPlan(artifact)) {
                continue;
            }
            String sessionId = text(artifact.get("correlationSessionId"));
            if (sessionId != null) {
                sessions.computeIfAbsent(sessionId, ignored -> new Session(artifact))
                        .add(text(outcome.get("planName")), artifact);
            }
        }
        return sessions.values().stream()
                .filter(session -> session.plans.size() > 1)
                .sorted(Comparator.comparing(session -> session.id))
                .map(this::summary)
                .toList();
    }

    /** Reports whether an aggregate cross-plan correlation failed closed. */
    public boolean failed(List<Map<String, Object>> summaries) {
        return summaries.stream().anyMatch(summary -> !"ok".equals(summary.get("status")));
    }

    private Map<String, Object> summary(Session session) {
        Match match = match(session);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("correlationSessionId", session.id);
        summary.put("status", match.success ? "ok" : "fail_closed");
        summary.put("reasonCode", match.reasonCode);
        summary.put("keyType", session.keyType);
        if (match.keyValue != null) {
            summary.put("keyValue", match.keyValue);
        }
        summary.put("contributingPlans", session.plans.stream().sorted().toList());
        return Map.copyOf(summary);
    }

    private Match match(Session session) {
        String keyValue = session.keyValue();
        if (session.keyTypes.size() > 1 || session.keyValues.size() > 1) {
            return Match.failure("ambiguous_cross_plan_correlation", keyValue);
        }
        if (keyValue == null) {
            return Match.failure("missing_correlation_key", null);
        }
        List<Map<String, Object>> matching = session.events.stream()
                .filter(event -> matches(event, session.keyType, keyValue))
                .sorted(eventComparator(session.expectedFlow))
                .toList();
        if (matching.isEmpty()) {
            return Match.failure("no_matching_events", keyValue);
        }
        if (windowExceeded(matching, session.maxWindowMs)) {
            return Match.failure("window_exceeded", keyValue);
        }
        if (ambiguousRuntime(matching)) {
            return Match.failure("ambiguous_cross_plan_correlation", keyValue);
        }
        if (firstMissingFlowIndex(matching, session.expectedFlow) >= 0) {
            return Match.failure("missing_expected_flow_event", keyValue);
        }
        return Match.success(keyValue);
    }

    private static boolean matches(Map<String, Object> event, String keyType, String keyValue) {
        if (!keyType.equals(event.get("keyType"))) {
            return false;
        }
        String eventValue = text(event.get("keyValue"));
        return keyValue.equals(eventValue) || fingerprint(keyValue).equals(event.get("keyFingerprint"));
    }

    private static Comparator<Map<String, Object>> eventComparator(List<String> flow) {
        return Comparator.comparingLong((Map<String, Object> event) -> number(event.get("timestampEpochMs")))
                .thenComparingInt(event -> {
                    int index = flow.indexOf(String.valueOf(event.get("probeId")));
                    return index < 0 ? Integer.MAX_VALUE : index;
                })
                .thenComparing(event -> String.valueOf(event.get("eventId")));
    }

    private static boolean windowExceeded(List<Map<String, Object>> events, long maxWindowMs) {
        if (maxWindowMs < 1L) {
            return true;
        }
        long first = number(events.getFirst().get("timestampEpochMs"));
        long last = number(events.getLast().get("timestampEpochMs"));
        return last - first > maxWindowMs;
    }

    private static boolean ambiguousRuntime(List<Map<String, Object>> events) {
        Set<String> identities = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            if ("runtime_line_hit".equals(event.get("eventType"))) {
                String identity = text(event.get("runtimeInstanceId"));
                if (identity != null) {
                    identities.add(identity);
                }
            }
        }
        return identities.size() > 1;
    }

    private static int firstMissingFlowIndex(
            List<Map<String, Object>> timeline, List<String> expectedFlow) {
        int timelineIndex = 0;
        for (int flowIndex = 0; flowIndex < expectedFlow.size(); flowIndex++) {
            while (timelineIndex < timeline.size()
                    && !expectedFlow.get(flowIndex).equals(timeline.get(timelineIndex).get("probeId"))) {
                timelineIndex++;
            }
            if (timelineIndex >= timeline.size()) {
                return flowIndex;
            }
            timelineIndex++;
        }
        return -1;
    }

    private static void putContext(
            Map<String, Object> context,
            String prefix,
            Session session,
            String planName,
            String keyValue) {
        context.put(prefix + ".keyValue", keyValue);
        context.put(prefix + ".keyType", session.keyType);
        context.put(prefix + ".correlationSessionId", session.id);
        context.put(prefix + ".sourcePlanName", planName);
    }

    private static Map<String, Object> correlation(Map<String, Object> outcome) {
        Object details = outcome.get("details");
        if (!(details instanceof Map<?, ?> values)) {
            return Map.of();
        }
        return stringMap(values.get("correlation"));
    }

    private static boolean crossPlan(Map<String, Object> artifact) {
        return Boolean.TRUE.equals(artifact.get("crossPlan"));
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, entry) -> values.put(String.valueOf(key), entry));
        return values;
    }

    private static List<Map<String, Object>> eventMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(RegressionCrossPlanCorrelation::stringMap)
                .filter(event -> !event.isEmpty()).toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(RegressionCrossPlanCorrelation::text)
                .filter(entry -> entry != null).toList();
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
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

    private static final class Session {

        private final String id;
        private final String keyType;
        private final long maxWindowMs;
        private final List<String> expectedFlow;
        private final Set<String> keyTypes = new LinkedHashSet<>();
        private final Set<String> keyValues = new LinkedHashSet<>();
        private final Set<String> plans = new LinkedHashSet<>();
        private final List<Map<String, Object>> events = new ArrayList<>();

        private Session(Map<String, Object> artifact) {
            this.id = text(artifact.get("correlationSessionId"));
            this.keyType = text(artifact.get("keyType"));
            this.maxWindowMs = number(stringMap(artifact.get("window")).get("maxWindowMs"));
            this.expectedFlow = strings(artifact.get("expectedFlow"));
        }

        private void add(String planName, Map<String, Object> artifact) {
            String artifactKeyType = text(artifact.get("keyType"));
            String artifactKeyValue = text(artifact.get("keyValue"));
            if (artifactKeyType != null) {
                keyTypes.add(artifactKeyType);
            }
            if (artifactKeyValue != null) {
                keyValues.add(artifactKeyValue);
            }
            if (planName != null) {
                plans.add(planName);
            }
            events.addAll(eventMaps(artifact.get("timeline")));
            events.stream().map(event -> text(event.get("keyValue")))
                    .filter(value -> value != null).forEach(keyValues::add);
        }

        private String keyValue() {
            return keyValues.size() == 1 ? keyValues.iterator().next() : null;
        }
    }

    private record Match(boolean success, String reasonCode, String keyValue) {

        private static Match success(String keyValue) {
            return new Match(true, "ok", keyValue);
        }

        private static Match failure(String reasonCode, String keyValue) {
            return new Match(false, reasonCode, keyValue);
        }
    }
}
