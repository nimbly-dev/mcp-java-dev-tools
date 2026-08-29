package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Records the durable SQLite cutover marker for one project. */
final class SqliteRunStateCutover {

    Map<String, Object> cutover(Path databasePath, String projectName) {
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath);
                Connection connection = SqliteRunStateDatabase.open(databasePath)) {
            SqliteRunStateDatabase.createMetadataTables(connection);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO store_metadata(key, value) VALUES('cutover', 'complete') "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                statement.executeUpdate();
            }
            Path marker = databasePath.getParent().resolve("state-store.cutover.json");
            writeAtomicMarker(marker, projectName);
            return Map.of(
                    "databasePath", databasePath.toString(),
                    "projectName", projectName,
                    "status", "cutover");
        } catch (SQLException | IOException exception) {
            throw new ArtifactOperationException("state_store_cutover_failed", "SQLite state-store cutover failed");
        }
    }

    void writeAtomicMarker(Path marker, String projectName) throws IOException {
        Path temporary = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".tmp");
        try {
            String content = "{\"projectName\":\"" + projectName + "\",\"status\":\"cutover_complete\"}\n";
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
