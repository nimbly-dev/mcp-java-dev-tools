package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** Owns SQLite connection setup, schema migration, and table discovery. */
final class SqliteRunStateDatabase {

    private static final int BUSY_TIMEOUT_MS = 5000;

    private SqliteRunStateDatabase() {
    }

    static Map<String, Object> ensure(Path databasePath, String projectName) {
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath);
                Connection connection = open(databasePath)) {
            createMetadataTables(connection);
            return Map.of(
                    "databasePath", databasePath.toString(),
                    "projectName", projectName,
                    "schemaVersion", 3);
        } catch (SQLException exception) {
            throw new ArtifactOperationException("state_store_unavailable", "SQLite state store is unavailable");
        }
    }

    static Connection open(Path databasePath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is unavailable", exception);
        }
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (Exception exception) {
            throw new SQLException("SQLite parent directory could not be created", exception);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    static void createMetadataTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS schema_migrations "
                            + "(version INTEGER PRIMARY KEY)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS store_metadata "
                            + "(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS plan_runs "
                            + "(plan_run_pk INTEGER PRIMARY KEY AUTOINCREMENT, project_name TEXT NOT NULL, "
                            + "suite_type TEXT NOT NULL DEFAULT 'regression', plan_name TEXT NOT NULL, run_id TEXT NOT NULL, status TEXT NOT NULL, "
                            + "step_count INTEGER, failed_step_count INTEGER, started_at_epoch_ms INTEGER, "
                            + "completed_at_epoch_ms INTEGER, revision INTEGER NOT NULL DEFAULT 0, "
                            + "reason_code TEXT, execution_profile TEXT, suite_run_id TEXT, active_phase TEXT, "
                            + "state_json TEXT, run_dir_path_rel TEXT NOT NULL, "
                            + "UNIQUE(project_name, suite_type, plan_name, run_id))");
            addSuiteTypeColumnIfMissing(connection);
            addColumnIfMissing(connection, "plan_runs", "execution_profile", "TEXT");
            addColumnIfMissing(connection, "plan_runs", "suite_run_id", "TEXT");
            addColumnIfMissing(connection, "plan_runs", "active_phase", "TEXT");
            addColumnIfMissing(connection, "plan_runs", "state_json", "TEXT");
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS plan_runs_identity "
                            + "ON plan_runs(project_name, suite_type, plan_name, run_id)");
            createStateTables(statement);
            statement.executeUpdate("INSERT OR IGNORE INTO schema_migrations(version) VALUES(3)");
        }
    }

    static void createStateTables(Statement statement) throws SQLException {
        statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS suite_continuation_state "
                        + "(project_name TEXT NOT NULL, suite_run_id TEXT NOT NULL, state_json TEXT NOT NULL, "
                        + "source_path_rel TEXT NOT NULL, UNIQUE(project_name, suite_run_id))");
        statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS correlation_state "
                        + "(project_name TEXT NOT NULL, plan_name TEXT NOT NULL, run_id TEXT NOT NULL, "
                        + "state_json TEXT NOT NULL, source_path_rel TEXT NOT NULL, "
                        + "UNIQUE(project_name, plan_name, run_id))");
        statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS watcher_state "
                        + "(project_name TEXT NOT NULL, plan_name TEXT NOT NULL, run_id TEXT NOT NULL, "
                        + "state_json TEXT NOT NULL, source_path_rel TEXT NOT NULL, "
                        + "UNIQUE(project_name, plan_name, run_id))");
        statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS external_verification_state "
                        + "(project_name TEXT NOT NULL, plan_name TEXT NOT NULL, run_id TEXT NOT NULL, "
                        + "state_json TEXT NOT NULL, source_path_rel TEXT NOT NULL, "
                        + "UNIQUE(project_name, plan_name, run_id))");
    }

    static void addSuiteTypeColumnIfMissing(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate(
                        "ALTER TABLE plan_runs ADD COLUMN suite_type TEXT NOT NULL DEFAULT 'regression'");
            } catch (SQLException exception) {
                if (!exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) {
                    throw exception;
                }
            }
        }
    }

    static void addColumnIfMissing(
            Connection connection, String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException exception) {
                String message = exception.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (!message.contains("duplicate column")) {
                    throw exception;
                }
            }
        }
    }

    static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
