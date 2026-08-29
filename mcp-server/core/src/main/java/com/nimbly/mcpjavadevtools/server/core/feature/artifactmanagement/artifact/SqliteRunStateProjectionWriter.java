package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Persists normalized run projections and their state-surface tables. */
final class SqliteRunStateProjectionWriter {

    private final SqliteRunStateJson json;

    SqliteRunStateProjectionWriter(SqliteRunStateJson json) {
        this.json = json;
    }

    void deleteProjectProjection(Connection connection, String projectName) throws SQLException {
        for (String table : List.of(
                "plan_runs", "suite_continuation_state", "correlation_state",
                "watcher_state", "external_verification_state")) {
            try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE project_name = ?")) {
                delete.setString(1, projectName);
                delete.executeUpdate();
            }
        }
    }

    void insertProjection(Connection connection, String projectName, SqliteRunProjection source)
            throws SQLException {
        try (var insert = connection.prepareStatement(
                "INSERT INTO plan_runs(project_name, suite_type, plan_name, run_id, status, step_count, "
                        + "failed_step_count, started_at_epoch_ms, completed_at_epoch_ms, reason_code, "
                        + "execution_profile, suite_run_id, active_phase, state_json, run_dir_path_rel) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(project_name, suite_type, plan_name, run_id) DO UPDATE SET status = excluded.status, "
                        + "step_count = excluded.step_count, failed_step_count = excluded.failed_step_count, "
                        + "started_at_epoch_ms = excluded.started_at_epoch_ms, "
                        + "completed_at_epoch_ms = excluded.completed_at_epoch_ms, "
                        + "reason_code = excluded.reason_code, execution_profile = excluded.execution_profile, "
                        + "suite_run_id = excluded.suite_run_id, active_phase = excluded.active_phase, "
                        + "state_json = excluded.state_json, run_dir_path_rel = excluded.run_dir_path_rel")) {
            insert.setString(1, projectName);
            insert.setString(2, source.suiteType());
            insert.setString(3, source.planName());
            insert.setString(4, source.runId());
            insert.setString(5, source.status());
            insert.setInt(6, source.stepCount());
            insert.setInt(7, source.failedStepCount());
            setNullableLong(insert, 8, source.startedAtEpochMs());
            setNullableLong(insert, 9, source.completedAtEpochMs());
            if (source.reasonCode() == null) {
                insert.setNull(10, Types.VARCHAR);
            } else {
                insert.setString(10, source.reasonCode());
            }
            insertNullable(insert, 11, source.executionProfile());
            insertNullable(insert, 12, source.suiteRunId());
            insertNullable(insert, 13, source.activePhase());
            insertNullable(insert, 14, json.boundedJson(source.artifact()));
            insert.setString(15, source.runDirPathRel());
            insert.executeUpdate();
        }
    }

    void insertStateSurfaces(Connection connection, String projectName, SqliteRunProjection source)
            throws SQLException {
        JsonNode root = source.artifact();
        insertState(connection, "correlation_state", projectName, source,
                firstPresent(root, "correlations", "correlation"));
        insertState(connection, "watcher_state", projectName, source,
                firstPresent(root, "watchers", "watcherResults", "watcherEvidence"));
        insertState(connection, "external_verification_state", projectName, source,
                firstPresent(root, "externalVerification", "externalVerificationResults"));
        JsonNode continuation = firstPresent(root, "continuation", "progressSummary");
        if (continuation != null && !continuation.isMissingNode() && !continuation.isNull()) {
            String encoded = json.boundedJson(continuation);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO suite_continuation_state(project_name, suite_run_id, state_json, source_path_rel) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT(project_name, suite_run_id) DO UPDATE SET "
                            + "state_json = excluded.state_json, source_path_rel = excluded.source_path_rel")) {
                insert.setString(1, projectName);
                insert.setString(2, root.path("suiteRunId").asText(source.runId()));
                insert.setString(3, encoded);
                insert.setString(4, source.runDirPathRel());
                insert.executeUpdate();
            }
        }
    }

    void insertState(
            Connection connection,
            String table,
            String projectName,
            SqliteRunProjection source,
            JsonNode value) throws SQLException {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        String encoded = json.boundedJson(value);
        try (var insert = connection.prepareStatement(
                "INSERT INTO " + table + "(project_name, plan_name, run_id, state_json, source_path_rel) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(project_name, plan_name, run_id) DO UPDATE SET "
                        + "state_json = excluded.state_json, source_path_rel = excluded.source_path_rel")) {
            insert.setString(1, projectName);
            insert.setString(2, source.planName());
            insert.setString(3, source.runId());
            insert.setString(4, encoded);
            insert.setString(5, source.runDirPathRel());
            insert.executeUpdate();
        }
    }

    JsonNode firstPresent(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    void insertNullable(java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
