package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds the SQLite projection from bounded filesystem Artifacts. */
final class SqliteRunStateProjectionRebuilder {

    private static final int MAX_REASON_ROWS = 1000;
    private final SqliteRunStateArtifactReader reader;
    private final SqliteRunStateProjectionWriter writer;

    SqliteRunStateProjectionRebuilder(
            SqliteRunStateArtifactReader reader, SqliteRunStateProjectionWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    Map<String, Object> rebuild(Path databasePath, String projectName, boolean strict) {
        Map<String, Object> ensured = SqliteRunStateDatabase.ensure(databasePath, projectName);
        List<String> reasons = new ArrayList<>();
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath);
                Connection connection = SqliteRunStateDatabase.open(databasePath)) {
            SqliteRebuildCounts counts = rebuildProjection(connection, databasePath, projectName, reasons);
            int scanned = counts.scanned();
            int rebuilt = counts.rebuilt();
            int invalid = counts.invalid();
            return result(ensured, scanned, rebuilt, invalid, reasons, strict);
        } catch (SQLException exception) {
            throw new ArtifactOperationException(
                    "state_store_rebuild_failed", "SQLite state-store rebuild failed");
        }
    }

    SqliteRebuildCounts rebuildProjection(
            Connection connection,
            Path databasePath,
            String projectName,
            List<String> reasons) throws SQLException {
        connection.setAutoCommit(false);
        int scanned = 0;
        int rebuilt = 0;
        int invalid = 0;
        try {
            SqliteRunStateDatabase.createMetadataTables(connection);
            writer.deleteProjectProjection(connection, projectName);
            for (SqliteRunProjection source : reader.scanRunArtifacts(databasePath)) {
                scanned++;
                if (source.valid()) {
                    writer.insertProjection(connection, projectName, source);
                    writer.insertStateSurfaces(connection, projectName, source);
                    rebuilt++;
                } else {
                    invalid++;
                    if (reasons.size() < MAX_REASON_ROWS) {
                        reasons.add(source.planName() + "/" + source.runId() + ":" + source.reason());
                    }
                }
            }
            connection.commit();
            return new SqliteRebuildCounts(scanned, rebuilt, invalid);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    Map<String, Object> result(
            Map<String, Object> ensured,
            int scanned,
            int rebuilt,
            int invalid,
            List<String> reasons,
            boolean strict) {
        if (strict && invalid > 0) {
            throw new ArtifactOperationException("state_store_rebuild_strict_failed",
                    "strict rebuild rejected invalid run Artifacts",
                    Map.of("invalidRuns", invalid, "reasons", reasons));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scannedRuns", scanned);
        summary.put("rebuiltRuns", rebuilt);
        summary.put("invalidRuns", invalid);
        summary.put("skippedRuns", 0);
        summary.put("conflictingRuns", 0);
        summary.put("reasons", reasons);
        Map<String, Object> result = new LinkedHashMap<>(ensured);
        result.put("status", "rebuilt");
        result.put("replayedRuns", rebuilt);
        result.put("summary", summary);
        return result;
    }
}
