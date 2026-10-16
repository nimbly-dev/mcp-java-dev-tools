package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Updates only the canonical run or suite checkpoint written by the current execution. */
class SqliteRunStateRuntimeProjection {
    private final SqliteRunStateArtifactReader reader;
    private final SqliteRunStateProjectionWriter writer;

    SqliteRunStateRuntimeProjection(SqliteRunStateArtifactReader reader, SqliteRunStateProjectionWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    void projectRun(Path database, String project, String suite, String plan, String run) {
        ArtifactPathPolicy paths = paths(database, project);
        Path directory = paths.resolve(".mcpjvm", project, "plans", suite, plan, "runs", run);
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(database);
                Connection connection = SqliteRunStateDatabase.open(database)) {
            SqliteRunProjection source = reader.readProjection(suite, plan, run,
                    paths.check(directory.resolve("execution.result.json")), paths.relative(directory), paths);
            if (!source.valid()) {
                throw new ArtifactOperationException("state_store_projection_source_invalid", "Run projection source is invalid");
            }
            SqliteRunStateDatabase.createMetadataTables(connection);
            connection.setAutoCommit(false);
            try {
                writer.insertProjection(connection, project, source);
                writer.insertStateSurfaces(connection, project, source);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new ArtifactOperationException("state_store_projection_failed", "Run JSON is persisted but its SQLite projection failed");
        }
    }

    void projectSuite(Path database, String project, String suiteRunId) {
        ArtifactPathPolicy paths = paths(database, project);
        Path source = paths.resolve(".mcpjvm", project, "suite-runs", suiteRunId, "execution_orchestration.result.json");
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(database);
                Connection connection = SqliteRunStateDatabase.open(database)) {
            JsonNode checkpoint = reader.readBoundedJson(source);
            if (checkpoint == null || !checkpoint.isObject() || !suiteRunId.equals(checkpoint.path("suiteRunId").asText())) {
                throw new ArtifactOperationException("state_store_projection_source_invalid", "Suite checkpoint source is invalid");
            }
            SqliteRunStateDatabase.createMetadataTables(connection);
            writer.insertSuiteContinuation(connection, project, suiteRunId,
                    checkpoint.path("progressSummary"), paths.relative(source));
        } catch (java.io.IOException | SQLException failure) {
            throw new ArtifactOperationException("state_store_projection_failed", "Suite JSON is persisted but its SQLite projection failed");
        }
    }

    private static ArtifactPathPolicy paths(Path database, String project) {
        Path absolute = database.toAbsolutePath().normalize();
        ArtifactPathPolicy paths = new ArtifactPathPolicy(absolute.getParent().getParent().getParent());
        if (!absolute.equals(paths.resolve(".mcpjvm", project, "run-state.sqlite"))) {
            throw new ArtifactOperationException("artifact_path_invalid", "SQLite path does not match the selected project");
        }
        paths.check(absolute.resolveSibling("run-state.sqlite.lock"));
        return paths;
    }
}
