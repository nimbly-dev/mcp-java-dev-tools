package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.Set;

/** Conservatively removes a newly provisioned state store after project creation fails. */
final class SqliteRunStateProvisioningCleanup {

    private static final Set<String> METADATA_TABLES = Set.of(
            "schema_migrations", "schema_migration_resources", "store_metadata");

    boolean removeIfEmpty(Path workspaceRoot, Path databasePath, String projectName) {
        Path quarantine = databasePath.resolveSibling(
                databasePath.getFileName() + ".provision-cleanup-" + System.nanoTime());
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath)) {
            try (Connection connection = SqliteRunStateDatabase.open(databasePath)) {
                beginExclusive(connection);
                if (!empty(connection) || markerPresent(workspaceRoot, databasePath, projectName)
                        || sideFilePresent(databasePath)) {
                    rollback(connection);
                    return false;
                }
                Files.move(databasePath, quarantine, StandardCopyOption.ATOMIC_MOVE);
                commit(connection);
            }
        } catch (Exception exception) {
            restore(databasePath, quarantine);
            return false;
        }
        deleteQuietly(quarantine);
        deleteQuietly(databasePath.resolveSibling(databasePath.getFileName() + ".lock"));
        return !Files.exists(quarantine) && !Files.exists(databasePath);
    }

    private void beginExclusive(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
        }
    }

    private void commit(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private void rollback(Connection connection) {
        try (var statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (Exception ignored) {
            // Closing the connection also rolls back an unfinished transaction.
        }
    }

    private void restore(Path databasePath, Path quarantine) {
        if (!Files.exists(quarantine) || Files.exists(databasePath)) {
            return;
        }
        try {
            Files.move(quarantine, databasePath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // Preserve the quarantine rather than deleting potentially recoverable state.
        }
    }

    private boolean empty(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
                var tables = statement.executeQuery()) {
            while (tables.next()) {
                String table = tables.getString(1);
                if (!METADATA_TABLES.contains(table) && !emptyTable(connection, table)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean emptyTable(Connection connection, String table) throws Exception {
        String identifier = table.replace("\"", "\"\"");
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM \"" + identifier + "\"")) {
            return result.next() && result.getLong(1) == 0;
        }
    }

    private boolean markerPresent(Path workspaceRoot, Path databasePath, String projectName) {
        return Files.exists(databasePath.getParent().resolve("state-store.cutover.json"))
                || Files.exists(workspaceRoot.resolve(".mcpjvm/state-store-cutovers")
                        .resolve(projectName + ".json"));
    }

    private boolean sideFilePresent(Path databasePath) {
        return Files.exists(databasePath.resolveSibling(databasePath.getFileName() + "-wal"))
                || Files.exists(databasePath.resolveSibling(databasePath.getFileName() + "-shm"));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Cleanup is deliberately best effort and must never hide the write failure.
        }
    }
}
