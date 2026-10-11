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
            Map<String, Object> result = executeWatcher(watcher, context);
            results.add(result);
            if (!"pass".equals(result.get("status"))) {
                return Result.failure(String.valueOf(result.get("reasonCode")), results);
            }
        }
        return Result.success(results);
    }

    private Map<String, Object> executeWatcher(JsonNode watcher, Map<String, Object> context) {
        JsonNode policy = watcher.path("waitPolicy");
        int attempts = bounded(policy.path("retryMax").asInt(3), 1, 100);
        long timeoutMs = bounded(policy.path("timeoutMs").asLong(5_000L), 1L, 60_000L);
        long intervalMs = bounded(policy.path("intervalMs").asLong(100L), 1L, 5_000L);
        long deadline = System.currentTimeMillis() + timeoutMs;
        RegressionVerificationSupport.Observation last = null;
        int attempt = 0;
        while (attempt < attempts && System.currentTimeMillis() <= deadline) {
            attempt++;
            last = support.executeHttp(
                    watcher.path("provider").path("transport").path("request"), context);
            if (!last.success()) {
                return row(watcher, "blocked_runtime", "watcher_target_unreachable", attempt, last);
            }
            if (support.firstExpectationFailure(watcher.path("expect"), last) == null) {
                return row(watcher, "pass", "ok", attempt, last);
            }
            if (attempt < attempts && System.currentTimeMillis() < deadline) {
                pause(Math.min(intervalMs, Math.max(1L, deadline - System.currentTimeMillis())));
            }
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
