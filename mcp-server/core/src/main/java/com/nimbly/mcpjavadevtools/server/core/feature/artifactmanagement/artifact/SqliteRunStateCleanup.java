package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Applies bounded terminal-run retention while preserving protected rows. */
final class SqliteRunStateCleanup {

    Map<String, Object> cleanup(Path databasePath, String projectName, boolean dryRun) {
        return cleanup(databasePath, projectName, dryRun, 90, 1000, 500);
    }

    Map<String, Object> cleanup(
            Path databasePath,
            String projectName,
            boolean dryRun,
            int terminalOlderThanDays,
            int keepMostRecentTerminalRuns,
            int maxDeleteBatch) {
        if (!Files.isRegularFile(databasePath)) {
            return Map.of("status", "ok", "dryRun", dryRun, "deletedRuns", 0);
        }
        try (SqliteRunStateLock ignored = SqliteRunStateLock.acquire(databasePath);
                Connection connection = SqliteRunStateDatabase.open(databasePath)) {
            if (!SqliteRunStateDatabase.hasTable(connection, "plan_runs")) {
                return Map.of("status", "ok", "dryRun", dryRun, "deletedRuns", 0);
            }
            long cutoff = System.currentTimeMillis() - terminalOlderThanDays * 86_400_000L;
            int deleted = 0;
            if (!dryRun) {
                try (var statement = connection.prepareStatement(
                        "DELETE FROM plan_runs WHERE plan_run_pk IN (SELECT candidate.plan_run_pk "
                                + "FROM plan_runs candidate WHERE candidate.project_name = ? "
                                + "AND candidate.status IN ('pass', 'fail', 'blocked', 'partial_fail') "
                                + "AND candidate.completed_at_epoch_ms IS NOT NULL "
                                + "AND candidate.completed_at_epoch_ms < ? "
                                + "AND candidate.plan_run_pk NOT IN (SELECT protected.plan_run_pk "
                                + "FROM plan_runs protected WHERE protected.project_name = ? "
                                + "AND protected.status IN ('pass', 'fail', 'blocked', 'partial_fail') "
                                + "ORDER BY protected.completed_at_epoch_ms DESC, protected.plan_run_pk DESC "
                                + "LIMIT ?) ORDER BY candidate.completed_at_epoch_ms, candidate.plan_run_pk LIMIT ?)")) {
                    statement.setString(1, projectName);
                    statement.setLong(2, cutoff);
                    statement.setString(3, projectName);
                    statement.setInt(4, keepMostRecentTerminalRuns);
                    statement.setInt(5, maxDeleteBatch);
                    deleted = statement.executeUpdate();
                }
            }
            return Map.of(
                    "status", "ok",
                    "dryRun", dryRun,
                    "deletedRuns", deleted,
                    "terminalOlderThanDays", terminalOlderThanDays,
                    "keepMostRecentTerminalRuns", keepMostRecentTerminalRuns,
                    "maxDeleteBatch", maxDeleteBatch);
        } catch (SQLException exception) {
            throw new ArtifactOperationException("state_store_cleanup_failed", "SQLite state-store cleanup failed");
        }
    }
}
