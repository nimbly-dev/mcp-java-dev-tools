package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;

/** Builds parameterized SQL for the supported run-state query surfaces. */
final class SqliteRunStateQueryPlanBuilder {

    SqliteRunStateQueryPlan build(String projectName, String table, JsonNode input) {
        JsonNode query = input == null || !input.isObject()
                ? JsonNodeFactory.instance.objectNode() : input;
        SqliteRunStateQueryContext context = new SqliteRunStateQueryContext(table, query, query.path("filters"),
                new ArrayList<>(), new ArrayList<>());
        context.predicates().add("project_name = ?");
        context.parameters().add(projectName);
        addQueryFilters(context);
        String where = String.join(" AND ", context.predicates());
        return new SqliteRunStateQueryPlan(
                "SELECT * FROM " + table + " WHERE " + where + queryOrder(context) + " LIMIT ? OFFSET ?",
                "SELECT COUNT(*) FROM " + table + " WHERE " + where,
                context.parameters());
    }

    void addQueryFilters(SqliteRunStateQueryContext context) {
        addColumnFilter(context, "planName", "plan_name");
        addColumnFilter(context, "runId", "run_id");
        addColumnFilter(context, "suiteRunId", "suite_run_id");
        addColumnFilter(context, "suiteType", "suite_type");
        addColumnFilter(context, "executionProfile", "execution_profile");
        addColumnFilter(context, "reasonCode", "reason_code");
        addColumnFilter(context, "activePhase", "active_phase");

        JsonNode status = SqliteRunStateQueryContract.firstNode(context.query(), context.filters(), "status");
        if (status != null && !status.isNull()) {
            if ("plan_runs".equals(context.table())) {
                addValueFilter(context, "status", status);
            } else {
                addStateValueFilter(context, "status", status);
            }
        }

        for (String field : List.of("correlationSessionId", "watcherName", "providerType", "outcome",
                "keyType", "keyValueExact", "keyValueSha256", "strictLineKey", "probeId",
                "logicalServiceId", "runtimeInstanceId")) {
            JsonNode expected = context.filters().path(field);
            if (!expected.isMissingNode() && !expected.isNull()) {
                addStateValueFilter(context, field, expected);
            }
        }
        addRangeFilter(context, "startedFromEpochMs", "started_at_epoch_ms", true);
        addRangeFilter(context, "startedToEpochMs", "started_at_epoch_ms", false);
        addRangeFilter(context, "completedFromEpochMs", "completed_at_epoch_ms", true);
        addRangeFilter(context, "completedToEpochMs", "completed_at_epoch_ms", false);
    }

    String queryOrder(SqliteRunStateQueryContext context) {
        String direction = SqliteRunStateQueryContract.sortDirection(context.query());
        String sortField = context.query().path("sort").path("field").asText("updatedAtEpochMs");
        String sortExpression;
        if ("plan_runs".equals(context.table())) {
            sortExpression = "startedAtEpochMs".equals(sortField)
                    ? "COALESCE(started_at_epoch_ms, 0)"
                    : "COALESCE(completed_at_epoch_ms, started_at_epoch_ms, 0)";
        } else {
            sortExpression = "CASE WHEN project_name IS NULL THEN 0 ELSE 0 END";
        }
        return " ORDER BY " + sortExpression + " " + direction
                + ", plan_name " + direction + ", run_id " + direction;
    }

    void addColumnFilter(SqliteRunStateQueryContext context, String inputField, String column) {
        JsonNode expected = SqliteRunStateQueryContract.firstNode(
                context.query(), context.filters(), inputField);
        if ((expected == null || expected.isNull()) && !"plan_name".equals(column)
                && !"run_id".equals(column)) {
            return;
        }
        if (expected == null || expected.isNull()) {
            return;
        }
        if ("plan_runs".equals(context.table()) || "plan_name".equals(column) || "run_id".equals(column)) {
            addValueFilter(context, column, expected);
        }
    }

    void addValueFilter(SqliteRunStateQueryContext context, String column, JsonNode expected) {
        List<String> values = SqliteRunStateQueryContract.textValues(expected);
        if (values.isEmpty()) {
            context.predicates().add("1 = 0");
            return;
        }
        context.predicates().add(column + " IN ("
                + SqliteRunStateQueryContract.placeholders(values.size()) + ")");
        context.parameters().addAll(values);
    }

    void addStateValueFilter(SqliteRunStateQueryContext context, String field, JsonNode expected) {
        List<String> values = SqliteRunStateQueryContract.textValues(expected);
        if (values.isEmpty()) {
            context.predicates().add("1 = 0");
            return;
        }
        context.predicates().add("EXISTS (SELECT 1 FROM json_tree(COALESCE(state_json, '{}')) AS state "
                + "WHERE state.key = ? AND CAST(state.value AS TEXT) IN ("
                + SqliteRunStateQueryContract.placeholders(values.size()) + "))");
        context.parameters().add(field);
        context.parameters().addAll(values);
    }

    void addRangeFilter(
            SqliteRunStateQueryContext context, String inputField, String column, boolean minimum) {
        JsonNode value = SqliteRunStateQueryContract.firstNode(
                context.query(), context.filters(), inputField);
        if (value == null || !value.canConvertToLong()) {
            return;
        }
        String operator;
        if (minimum) {
            operator = " >= ?";
        } else {
            operator = " <= ?";
        }
        if ("plan_runs".equals(context.table())) {
            context.predicates().add(column + operator);
            context.parameters().add(value.longValue());
        } else {
            String stateField = stateRangeField(inputField);
            context.predicates().add("EXISTS (SELECT 1 FROM json_tree(COALESCE(state_json, '{}')) AS state "
                    + "WHERE state.key = ? AND CAST(state.value AS INTEGER) "
                    + operator.trim() + ")");
            context.parameters().add(stateField);
            context.parameters().add(value.longValue());
        }
    }

    String stateRangeField(String inputField) {
        if (inputField.startsWith("started")) {
            return "startedAtEpochMs";
        }
        if (inputField.startsWith("completed")) {
            return "completedAtEpochMs";
        }
        if (inputField.startsWith("correlated")) {
            return "correlatedAtEpochMs";
        }
        return "deadlineAtEpochMs";
    }
}
