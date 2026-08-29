package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;

/** Bounded SQLite lifecycle port for run-state Artifacts. */
public final class SqliteRunStateStore {

    private final SqliteRunStateQuery query;
    private final SqliteRunStateProjectionRebuilder projection;
    private final SqliteRunStateLegacyBackfill backfill;
    private final SqliteRunStateCutover cutover = new SqliteRunStateCutover();
    private final SqliteRunStateCleanup cleanup = new SqliteRunStateCleanup();

    /** Creates a store with the Core JSON parser. */
    public SqliteRunStateStore() {
        this(new ObjectMapper());
    }

    /** Creates a store with the supplied JSON parser. */
    public SqliteRunStateStore(ObjectMapper mapper) {
        SqliteRunStateJson json = new SqliteRunStateJson(mapper);
        SqliteRunStateArtifactReader reader = new SqliteRunStateArtifactReader(json);
        SqliteRunStateProjectionWriter writer = new SqliteRunStateProjectionWriter(json);
        query = new SqliteRunStateQuery(mapper);
        projection = new SqliteRunStateProjectionRebuilder(reader, writer);
        backfill = new SqliteRunStateLegacyBackfill(reader, json);
    }

    /** Ensures a project-owned SQLite store and its projection tables exist. */
    public Map<String, Object> ensure(Path databasePath, String projectName) {
        return SqliteRunStateDatabase.ensure(databasePath, projectName);
    }

    /** Returns the selected page after the query contract has been applied. */
    public Map<String, Object> query(Path databasePath, String projectName) {
        return query(databasePath, projectName, "run_state");
    }

    /** Returns a bounded state-surface projection for run, correlation, Watcher, or verification state. */
    public Map<String, Object> query(Path databasePath, String projectName, String stateSurface) {
        return query(databasePath, projectName, stateSurface, null);
    }

    /** Executes the bounded TypeScript-compatible run-state query contract. */
    public Map<String, Object> query(
            Path databasePath, String projectName, String stateSurface, JsonNode input) {
        return query.query(databasePath, projectName, stateSurface, input);
    }

    /** Rebuilds the plan-run projection from persisted run Artifacts. */
    public Map<String, Object> rebuild(Path databasePath, String projectName) {
        return rebuild(databasePath, projectName, false);
    }

    /** Rebuilds the projection and fails closed when strict mode sees invalid sources. */
    public Map<String, Object> rebuild(Path databasePath, String projectName, boolean strict) {
        return projection.rebuild(databasePath, projectName, strict);
    }

    /** Backfills a bounded legacy correlation inventory before cutover. */
    public Map<String, Object> backfill(Path databasePath, String projectName) {
        return backfill.backfill(databasePath, projectName);
    }

    /** Records a deterministic cutover marker in the store and project directory. */
    public Map<String, Object> cutover(Path databasePath, String projectName) {
        return cutover.cutover(databasePath, projectName);
    }

    /** Returns cleanup preview and applies only an explicitly non-dry-run request. */
    public Map<String, Object> cleanup(Path databasePath, String projectName, boolean dryRun) {
        return cleanup.cleanup(databasePath, projectName, dryRun);
    }

    /** Applies bounded terminal-run retention while preserving the newest protected rows. */
    public Map<String, Object> cleanup(
            Path databasePath,
            String projectName,
            boolean dryRun,
            int terminalOlderThanDays,
            int keepMostRecentTerminalRuns,
            int maxDeleteBatch) {
        return cleanup.cleanup(databasePath, projectName, dryRun, terminalOlderThanDays,
                keepMostRecentTerminalRuns, maxDeleteBatch);
    }
}
