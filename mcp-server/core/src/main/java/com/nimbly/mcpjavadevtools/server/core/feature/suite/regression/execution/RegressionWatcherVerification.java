package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes bounded dependency-aware HTTP Watchers after Regression triggers. */
final class RegressionWatcherVerification {

    private final RegressionVerificationSupport support;

    RegressionWatcherVerification(RegressionVerificationSupport support) {
        this.support = support;
    }

    Result execute(
            JsonNode contract,
            Map<String, Object> context,
            Map<Integer, Map<String, Object>> outputs) {
        return execute(contract, context, outputs, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(), 100);
    }

    Result execute(JsonNode contract, Map<String, Object> context,
            Map<Integer, Map<String, Object>> outputs, JsonNode prior, int batchSize) {
        JsonNode watchers = contract.path("watchers");
        if (!watchers.isArray() || watchers.isEmpty()) {
            return Result.notConfigured();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode watcher : watchers) {
            int dependency = watcher.path("dependency").path("stepOrder").asInt(0);
            Map<String, Object> dependencyOutput = outputs.get(dependency);
            if (dependencyOutput == null || !"pass".equals(dependencyOutput.get("status"))) {
                return Result.failure("watcher_dependency_invalid", results);
            }
            JsonNode previous = previous(prior, watcher.path("id").asText());
            Map<String, Object> result = executeWatcher(watcher, context, previous, batchSize);
            results.add(result);
            if (!"pass".equals(result.get("status"))) {
                return Result.failure(String.valueOf(result.get("reasonCode")), results);
            }
        }
        return Result.success(results);
    }

    private JsonNode previous(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> executeWatcher(JsonNode watcher, Map<String, Object> context, JsonNode prior, int batchSize) {
        if (prior != null && "pass".equals(prior.path("status").asText())) {
            return support.object(prior);
        }
        JsonNode policy = watcher.path("waitPolicy");
        int attempts = bounded(policy.path("retryMax").asInt(3), 1, 100);
        long timeoutMs = bounded(policy.path("timeoutMs").asLong(5_000L), 1L, 60_000L);
        long intervalMs = bounded(policy.path("intervalMs").asLong(100L), 1L, 5_000L);
        long deadline = prior == null ? System.currentTimeMillis() + timeoutMs : prior.path("deadlineEpochMs").asLong();
        RegressionVerificationSupport.Observation last = null;
        int attempt = prior == null ? 0 : prior.path("attemptCount").asInt();
        int stopAt = Math.min(attempts, attempt + batchSize);
        while (attempt < stopAt && System.currentTimeMillis() <= deadline) {
            attempt++;
            last = support.executeHttp(
                    watcher.path("provider").path("transport").path("request"), context,
                    Math.max(1L, deadline - System.currentTimeMillis()));
            if (System.currentTimeMillis() >= deadline) {
                return row(watcher, "fail_assertion", "watcher_timeout", attempt, last);
            }
            if (!last.success()) {
                return row(watcher, "blocked_runtime", "watcher_target_unreachable", attempt, last);
            }
            if (support.firstEnvelopeExpectationFailure(watcher.path("expect"), Map.of("response", last.response()), context) == null) {
                return row(watcher, "pass", "ok", attempt, last);
            }
            if (attempt < attempts && System.currentTimeMillis() < deadline) {
                pause(Math.min(intervalMs, Math.max(1L, deadline - System.currentTimeMillis())));
            }
        }
        if (attempt < attempts && System.currentTimeMillis() <= deadline) {
            Map<String, Object> waiting = new LinkedHashMap<>(row(watcher, "in_progress", "watcher_in_progress", attempt, last));
            waiting.put("deadlineEpochMs", deadline);
            return Map.copyOf(waiting);
        }
        String reason = System.currentTimeMillis() > deadline
                ? "watcher_timeout" : "watcher_expectation_failed";
        return row(watcher, "fail_assertion", reason, attempt, last);
    }

    private Map<String, Object> row(
            JsonNode watcher,
            String status,
            String reasonCode,
            int attempts,
            RegressionVerificationSupport.Observation observation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", watcher.path("id").asText());
        row.put("status", status);
        row.put("reasonCode", reasonCode);
        row.put("attemptCount", attempts);
        if (observation != null) {
            row.put("durationMs", observation.durationMs());
            if (!observation.response().isEmpty()) {
                row.put("response", observation.response());
            }
        }
        return Map.copyOf(row);
    }

    private void pause(long durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Result(boolean configured, boolean success, String reasonCode, List<Map<String, Object>> details) {
        private static Result notConfigured() {
            return new Result(false, true, "ok", List.of());
        }

        private static Result success(List<Map<String, Object>> details) {
            return new Result(true, true, "ok", List.copyOf(details));
        }

        private static Result failure(String reasonCode, List<Map<String, Object>> details) {
            return new Result(true, false, reasonCode, List.copyOf(details));
        }
    }
}
