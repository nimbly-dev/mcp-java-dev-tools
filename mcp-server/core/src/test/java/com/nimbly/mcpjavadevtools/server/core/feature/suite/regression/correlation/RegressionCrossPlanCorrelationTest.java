package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegressionCrossPlanCorrelationTest {

    private final RegressionCrossPlanCorrelation correlation = new RegressionCrossPlanCorrelation();

    @Test
    void promotesResolvedKeyIntoLastAndSessionSpecificContext() {
        Map<String, Object> context = new LinkedHashMap<>();

        correlation.promote(context, "producer-plan", outcome(
                "producer-plan", "event-flow", "evt-123", event("producer", "evt-123", 100L)));

        assertThat(context)
                .containsEntry("suite.correlation.last.keyValue", "evt-123")
                .containsEntry("suite.correlation.last.sourcePlanName", "producer-plan")
                .containsEntry("suite.correlation.event-flow.keyValue", "evt-123")
                .containsEntry("suite.correlation.event-flow.keyType", "messageId");
    }

    @Test
    void aggregatesOrderedEventsFromTwoPersistedPlanOutcomes() {
        List<Map<String, Object>> outcomes = List.of(
                outcome("producer-plan", "event-flow", "evt-123", event("producer", "evt-123", 100L)),
                outcome("consumer-plan", "event-flow", "evt-123", event("consumer", "evt-123", 120L)));

        List<Map<String, Object>> summaries = correlation.summarize(outcomes);

        assertThat(summaries).singleElement().satisfies(summary -> assertThat(summary)
                .containsEntry("status", "ok")
                .containsEntry("reasonCode", "ok")
                .containsEntry("keyValue", "evt-123")
                .containsEntry("contributingPlans", List.of("consumer-plan", "producer-plan")));
        assertThat(correlation.failed(summaries)).isFalse();
    }

    @Test
    void failsClosedWhenPersistedPlansContributeDifferentKeys() {
        List<Map<String, Object>> outcomes = List.of(
                outcome("producer-plan", "event-flow", "evt-123", event("producer", "evt-123", 100L)),
                outcome("consumer-plan", "event-flow", "evt-456", event("consumer", "evt-456", 120L)));

        List<Map<String, Object>> summaries = correlation.summarize(outcomes);

        assertThat(summaries).singleElement().satisfies(summary -> assertThat(summary)
                .containsEntry("status", "fail_closed")
                .containsEntry("reasonCode", "ambiguous_cross_plan_correlation"));
        assertThat(correlation.failed(summaries)).isTrue();
    }

    private static Map<String, Object> outcome(
            String planName,
            String sessionId,
            String keyValue,
            Map<String, Object> event) {
        Map<String, Object> artifact = Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("reasonCode", "ok"),
                Map.entry("crossPlan", true),
                Map.entry("correlationSessionId", sessionId),
                Map.entry("keyType", "messageId"),
                Map.entry("keyValue", keyValue),
                Map.entry("window", Map.of("maxWindowMs", 5_000)),
                Map.entry("expectedFlow", List.of("producer", "consumer")),
                Map.entry("timeline", List.of(event)));
        return Map.of(
                "planName", planName,
                "status", "ready",
                "details", Map.of("runStatus", "pass", "correlation", artifact));
    }

    private static Map<String, Object> event(
            String probeId, String keyValue, long timestampEpochMs) {
        return Map.of(
                "eventId", probeId + ":1",
                "probeId", probeId,
                "timestampEpochMs", timestampEpochMs,
                "keyType", "messageId",
                "keyValue", keyValue,
                "eventType", "http");
    }
}
