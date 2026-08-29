package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Imports legacy correlation Artifacts with an idempotent audit trail. */
final class SqliteRunStateLegacyBackfill {

    private final SqliteRunStateArtifactReader reader;
    private final SqliteRunStateJson json;

    SqliteRunStateLegacyBackfill(SqliteRunStateArtifactReader reader, SqliteRunStateJson json) {
        this.reader = reader;
        this.json = json;
    }

    Map<String, Object> backfill(Path databasePath, String projectName) {
        Map<String, Object> ensured = SqliteRunStateDatabase.ensure(databasePath, projectName);
        int imported = 0;
        Path projectDirectory = databasePath.getParent();
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath);
                Connection connection = SqliteRunStateDatabase.open(databasePath)) {
            connection.setAutoCommit(false);
            createLegacyAuditTables(connection);
            imported = importLegacyCorrelations(connection, projectName, projectDirectory, databasePath);
            connection.commit();
        } catch (SQLException | IOException | RuntimeException exception) {
            throw new ArtifactOperationException(
                    "state_store_backfill_failed", "SQLite legacy backfill failed");
        }
        Map<String, Object> result = new LinkedHashMap<>(ensured);
        result.put("status", "backfilled");
        result.put("imported", imported);
        return result;
    }

    void createLegacyAuditTables(Connection connection) throws SQLException {
        try (var create = connection.createStatement()) {
            create.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS legacy_backfill_audits "
                            + "(audit_id INTEGER PRIMARY KEY AUTOINCREMENT, project_name TEXT NOT NULL, "
                            + "source_path_rel TEXT NOT NULL, imported_at_epoch_ms INTEGER NOT NULL, "
                            + "UNIQUE(project_name, source_path_rel))");
            create.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS legacy_backfill_audits_identity "
                            + "ON legacy_backfill_audits(project_name, source_path_rel)");
        }
    }

    int importLegacyCorrelations(
            Connection connection,
            String projectName,
            Path projectDirectory,
            Path databasePath) throws SQLException, IOException {
        int imported = 0;
        for (Path correlation : reader.findFiles(projectDirectory, "correlation.json")) {
            imported += importLegacyCorrelation(connection, projectName, databasePath, correlation);
        }
        return imported;
    }

    int importLegacyCorrelation(
            Connection connection,
            String projectName,
            Path databasePath,
            Path correlation) throws SQLException, IOException {
        String sourcePath = reader.relativeToWorkspace(databasePath, correlation);
        if (!recordLegacyAudit(connection, projectName, sourcePath)) {
            return 0;
        }
        SqliteLegacyCorrelation legacy = reader.legacyCorrelation(correlation);
        String stateJson = json.boundedJson(reader.readBoundedJson(correlation));
        try (var insert = connection.prepareStatement(
                "INSERT INTO correlation_state(project_name, plan_name, run_id, state_json, source_path_rel) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(project_name, plan_name, run_id) DO UPDATE SET "
                        + "state_json = excluded.state_json, source_path_rel = excluded.source_path_rel")) {
            insert.setString(1, projectName);
            insert.setString(2, legacy.planName());
            insert.setString(3, legacy.runId());
            insert.setString(4, stateJson);
            insert.setString(5, sourcePath);
            insert.executeUpdate();
        }
        return 1;
    }

    boolean recordLegacyAudit(Connection connection, String projectName, String sourcePath)
            throws SQLException {
        try (var audit = connection.prepareStatement(
                "INSERT INTO legacy_backfill_audits(project_name, source_path_rel, imported_at_epoch_ms) "
                        + "VALUES (?, ?, ?) ON CONFLICT(project_name, source_path_rel) DO NOTHING")) {
            audit.setString(1, projectName);
            audit.setString(2, sourcePath);
            audit.setLong(3, System.currentTimeMillis());
            return audit.executeUpdate() > 0;
        }
    }
}
