package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps SQLite rows to the released redacted run-state response shape. */
final class SqliteRunStateRowMapper {

    private final ObjectMapper mapper;

    SqliteRunStateRowMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> publicRow(Map<String, Object> row, JsonNode query) {
        Map<String, Object> output = new LinkedHashMap<>();
        putIfPresent(output, "stateKind", row.get("state_kind"));
        putIfPresent(output, "projectName", row.get("project_name"));
        putIfPresent(output, "suiteType", row.get("suite_type"));
        putIfPresent(output, "planName", row.get("plan_name"));
        putIfPresent(output, "runId", row.get("run_id"));
        putIfPresent(output, "suiteRunId", row.get("suite_run_id"));
        putIfPresent(output, "status", row.get("status"));
        putIfPresent(output, "executionProfile", row.get("execution_profile"));
        putIfPresent(output, "activePhase", row.get("active_phase"));
        putIfPresent(output, "stepCount", row.get("step_count"));
        putIfPresent(output, "failedStepCount", row.get("failed_step_count"));
        putIfPresent(output, "startedAtEpochMs", row.get("started_at_epoch_ms"));
        putIfPresent(output, "completedAtEpochMs", row.get("completed_at_epoch_ms"));
        putIfPresent(output, "reasonCode", row.get("reason_code"));
        Object completed = row.get("completed_at_epoch_ms");
        Object started = row.get("started_at_epoch_ms");
        output.put("updatedAtEpochMs", completed instanceof Number ? completed : started);
        putIfPresent(output, "runDirPathRel", row.get("run_dir_path_rel"));
        if (row.get("state_json") != null && !output.containsKey("status")) {
            try {
                output.put("state", mapper.readTree(String.valueOf(row.get("state_json"))));
            } catch (IOException exception) {
                throw new ArtifactOperationException("run_state_detail_invalid", "run-state detail is invalid");
            }
        }
        addDetails(output, row, query);
        return output;
    }

    void addDetails(Map<String, Object> output, Map<String, Object> row, JsonNode query) {
        JsonNode detail = query == null ? null : query.path("detail");
        boolean hasDetail = detail != null && detail.isObject() && detail.size() > 0;
        boolean hasWindows = query != null && (query.path("watchers").isObject()
                || query.path("watcherEvidence").isObject());
        if (!hasDetail && !hasWindows) {
            return;
        }
        try {
            JsonNode state = row.get("state_json") == null
                    ? mapper.createObjectNode() : mapper.readTree(String.valueOf(row.get("state_json")));
            Map<String, Object> selected = selectedDetails(state, detail, hasDetail);
            if (hasDetail) {
                output.put("detail", ArtifactRedactionPolicy.sanitizeMap(selected));
            }
            addStateWindows(output, state, query);
        } catch (IOException exception) {
            throw new ArtifactOperationException("run_state_detail_invalid", "run-state detail is invalid");
        }
    }

    Map<String, Object> selectedDetails(JsonNode state, JsonNode detail, boolean hasDetail) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (!hasDetail) {
            return selected;
        }
        for (String field : List.of("continuation", "observations", "assertions", "ownerLease")) {
            if (detail.path(field).asBoolean(false)) {
                selected.put(field, state.get(field));
            }
        }
        for (String field : List.of("keys", "lineExpectations", "probeObservations", "attempts")) {
            addWindow(selected, state, detail, field);
        }
        if (detail.path("select").isArray()) {
            for (JsonNode selector : detail.path("select")) {
                if (selector.isTextual() && state.has(selector.asText())) {
                    selected.put(selector.asText(), state.get(selector.asText()));
                }
            }
        }
        return selected;
    }

    void addStateWindows(Map<String, Object> output, JsonNode state, JsonNode query) {
        if (query.path("watchers").isObject()) {
            JsonNode watchers = window(state.get("watchers"), query.path("watchers"));
            output.put("watchers", filterWatchers(watchers, query.path("watcherFilter")));
        }
        if (query.path("watcherEvidence").isObject()) {
            output.put("watcherEvidence", window(state.get("watcherEvidence"), query.path("watcherEvidence")));
        }
    }

    void addWindow(Map<String, Object> selected, JsonNode state, JsonNode detail, String field) {
        if (detail.path(field).isObject()) {
            selected.put(field, window(state.get(field), detail.path(field)));
        }
    }

    JsonNode window(JsonNode value, JsonNode options) {
        if (value == null || !value.isArray()) {
            return value;
        }
        int offset = Math.max(0, options.path("offset").asInt(0));
        int limit = Math.min(250, Math.max(1, options.path("limit").asInt(25)));
        var output = JsonNodeFactory.instance.arrayNode();
        for (int index = offset; index < Math.min(value.size(), offset + limit); index++) {
            output.add(value.get(index));
        }
        return output;
    }

    JsonNode filterWatchers(JsonNode value, JsonNode filter) {
        if (filter == null || !filter.isObject() || value == null || !value.isArray()) {
            return value;
        }
        String watcherId = filter.path("watcherId").asText(null);
        String status = filter.path("watcherStatus").asText(null);
        var output = JsonNodeFactory.instance.arrayNode();
        for (JsonNode watcher : value) {
            if (watcherId != null && !watcherId.equals(watcher.path("watcherId").asText(null))) {
                continue;
            }
            if (status != null && !status.equals(watcher.path("status").asText(null))) {
                continue;
            }
            output.add(watcher);
        }
        return output;
    }

    void putIfPresent(Map<String, Object> output, String name, Object value) {
        if (value != null) {
            output.put(name, value);
        }
    }
}
