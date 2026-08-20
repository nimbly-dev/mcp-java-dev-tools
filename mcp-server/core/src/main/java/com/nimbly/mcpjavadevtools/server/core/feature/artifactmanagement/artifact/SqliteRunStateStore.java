package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/** Bounded SQLite lifecycle port for run-state Artifacts. */
public final class SqliteRunStateStore {

    private static final int MAX_ROWS = 1000;
    private static final int MAX_SCAN_ENTRIES = 10_000;
    private static final int BUSY_TIMEOUT_MS = 5000;
    private final ObjectMapper mapper;

    /** Creates a store with the Core JSON parser. */
    public SqliteRunStateStore() {
        this(new ObjectMapper());
    }

    /** Creates a store with the supplied JSON parser. */
    public SqliteRunStateStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Ensures a project-owned SQLite store and its projection tables exist. */
    public Map<String, Object> ensure(Path databasePath, String projectName) {
        try (StoreLock ignored = acquireLock(databasePath); Connection connection = open(databasePath)) {
            createMetadataTables(connection);
            return Map.of(
                    "databasePath", databasePath.toString(),
                    "projectName", projectName,
                    "schemaVersion", 3);
        } catch (SQLException exception) {
            throw new ArtifactOperationException("state_store_unavailable", "SQLite state store is unavailable");
        }
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
            Path databasePath, String projectName, String stateSurface, JsonNode query) {
        if (!Files.isRegularFile(databasePath)) {
            return Map.of("source", "sqlite", "status", "unavailable", "items", List.of());
        }
        try (Connection connection = open(databasePath)) {
            String table = tableFor(stateSurface);
            if (!hasTable(connection, table)) {
                return Map.of("source", "sqlite", "status", "available", "items", List.of());
            }
            int pageSize = pageSize(query);
            int offset = cursorOffset(query);
            QueryPlan queryPlan = queryPlan(projectName, table, query);
            List<Map<String, Object>> rows = readRows(connection, queryPlan, pageSize, offset);
            if (rows.isEmpty() && offset > 0 && countRows(connection, queryPlan) < offset) {
                throw new ArtifactOperationException("run_state_cursor_invalid", "run-state cursor is invalid");
            }
            boolean hasMore = rows.size() > pageSize;
            List<Map<String, Object>> page = new ArrayList<>();
            for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), pageSize))) {
                page.add(publicRow(row, query));
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("source", "sqlite");
            output.put("status", "available");
            output.put("stateSurface", stateSurface);
            output.put("projectName", projectName);
            output.put("projectionVersion", 1);
            output.put("strict", query != null && query.path("strict").asBoolean(false));
            output.put("pageSize", pageSize);
            output.put("hasMore", hasMore);
            output.put("sort", Map.of("field", "updatedAtEpochMs", "direction", sortDirection(query)));
            output.put("items", page);
            if (hasMore) {
                output.put("nextCursor", encodeCursor(offset + pageSize, sortDirection(query)));
            }
            return output;
        } catch (SQLException exception) {
            throw new ArtifactOperationException("state_store_corrupt", "SQLite state store could not be queried");
        }
    }

    /** Rebuilds the plan-run projection from persisted run Artifacts. */
    public Map<String, Object> rebuild(Path databasePath, String projectName) {
        return rebuild(databasePath, projectName, false);
    }

    /** Rebuilds the projection and fails closed when strict mode sees invalid sources. */
    public Map<String, Object> rebuild(Path databasePath, String projectName, boolean strict) {
        Map<String, Object> ensured = ensure(databasePath, projectName);
        int scanned = 0;
        int rebuilt = 0;
        int invalid = 0;
        List<String> reasons = new ArrayList<>();
        try (StoreLock ignored = acquireLock(databasePath); Connection connection = open(databasePath)) {
            RebuildCounts counts = rebuildProjection(connection, databasePath, projectName, reasons);
            scanned = counts.scanned();
            rebuilt = counts.rebuilt();
            invalid = counts.invalid();
        } catch (SQLException exception) {
            throw new ArtifactOperationException(
                    "state_store_rebuild_failed", "SQLite state-store rebuild failed");
        }
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

    private RebuildCounts rebuildProjection(
            Connection connection,
            Path databasePath,
            String projectName,
            List<String> reasons) throws SQLException {
        connection.setAutoCommit(false);
        int scanned = 0;
        int rebuilt = 0;
        int invalid = 0;
        try {
            createMetadataTables(connection);
            deleteProjectProjection(connection, projectName);
            for (RunProjection source : scanRunArtifacts(databasePath)) {
                scanned++;
                if (source.valid()) {
                    insertProjection(connection, projectName, source);
                    insertStateSurfaces(connection, projectName, source);
                    rebuilt++;
                } else {
                    invalid++;
                    if (reasons.size() < MAX_ROWS) {
                        reasons.add(source.planName() + "/" + source.runId() + ":" + source.reason());
                    }
                }
            }
            connection.commit();
            return new RebuildCounts(scanned, rebuilt, invalid);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Backfills a bounded legacy correlation inventory before cutover. */
    public Map<String, Object> backfill(Path databasePath, String projectName) {
        Map<String, Object> ensured = ensure(databasePath, projectName);
        int imported = 0;
        Path projectDirectory = databasePath.getParent();
        try (StoreLock ignored = acquireLock(databasePath); Connection connection = open(databasePath)) {
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

    private static void createLegacyAuditTables(Connection connection) throws SQLException {
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

    private int importLegacyCorrelations(
            Connection connection,
            String projectName,
            Path projectDirectory,
            Path databasePath) throws SQLException, IOException {
        int imported = 0;
        for (Path correlation : findFiles(projectDirectory, "correlation.json")) {
            imported += importLegacyCorrelation(connection, projectName, databasePath, correlation);
        }
        return imported;
    }

    private int importLegacyCorrelation(
            Connection connection,
            String projectName,
            Path databasePath,
            Path correlation) throws SQLException, IOException {
        String sourcePath = relativeToWorkspace(databasePath, correlation);
        if (!recordLegacyAudit(connection, projectName, sourcePath)) {
            return 0;
        }
        LegacyCorrelation legacy = legacyCorrelation(correlation);
        String stateJson = boundedJson(readBoundedJson(correlation));
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

    private static boolean recordLegacyAudit(
            Connection connection,
            String projectName,
            String sourcePath) throws SQLException {
        try (var audit = connection.prepareStatement(
                "INSERT INTO legacy_backfill_audits(project_name, source_path_rel, imported_at_epoch_ms) "
                        + "VALUES (?, ?, ?) ON CONFLICT(project_name, source_path_rel) DO NOTHING")) {
            audit.setString(1, projectName);
            audit.setString(2, sourcePath);
            audit.setLong(3, System.currentTimeMillis());
            return audit.executeUpdate() > 0;
        }
    }

    /** Records a deterministic cutover marker in the store and project directory. */
    public Map<String, Object> cutover(Path databasePath, String projectName) {
        try (StoreLock ignored = acquireLock(databasePath); Connection connection = open(databasePath)) {
            createMetadataTables(connection);
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

    /** Returns cleanup preview and applies only an explicitly non-dry-run request. */
    public Map<String, Object> cleanup(Path databasePath, String projectName, boolean dryRun) {
        return cleanup(databasePath, projectName, dryRun, 90, 1000, 500);
    }

    /** Applies bounded terminal-run retention while preserving the newest protected rows. */
    public Map<String, Object> cleanup(
            Path databasePath,
            String projectName,
            boolean dryRun,
            int terminalOlderThanDays,
            int keepMostRecentTerminalRuns,
            int maxDeleteBatch) {
        if (!Files.isRegularFile(databasePath)) {
            return Map.of("status", "ok", "dryRun", dryRun, "deletedRuns", 0);
        }
        try (StoreLock ignored = acquireLock(databasePath); Connection connection = open(databasePath)) {
            if (!hasTable(connection, "plan_runs")) {
                return Map.of("status", "ok", "dryRun", dryRun, "deletedRuns", 0);
            }
            long cutoff = System.currentTimeMillis() - terminalOlderThanDays * 86_400_000L;
            int deleted = 0;
            if (!dryRun) {
                try (var statement = connection.prepareStatement(
                        "DELETE FROM plan_runs WHERE project_name = ? AND status IN "
                                + "('pass', 'fail', 'blocked', 'partial_fail') "
                                + "AND completed_at_epoch_ms IS NOT NULL AND completed_at_epoch_ms < ? "
                                + "AND plan_run_pk NOT IN (SELECT plan_run_pk FROM plan_runs "
                                + "WHERE project_name = ? AND status IN ('pass', 'fail', 'blocked', 'partial_fail') "
                                + "ORDER BY completed_at_epoch_ms DESC, plan_run_pk DESC LIMIT ?) LIMIT ?")) {
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

    private static Connection open(Path databasePath) throws SQLException {
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

    private static void createMetadataTables(Connection connection) throws SQLException {
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

    private static void createStateTables(Statement statement) throws SQLException {
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

    private static void addSuiteTypeColumnIfMissing(Connection connection) throws SQLException {
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

    private static void addColumnIfMissing(
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

    private static void deleteProjectProjection(Connection connection, String projectName) throws SQLException {
        for (String table : List.of(
                "plan_runs", "suite_continuation_state", "correlation_state",
                "watcher_state", "external_verification_state")) {
            try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE project_name = ?")) {
                delete.setString(1, projectName);
                delete.executeUpdate();
            }
        }
    }

    private void insertProjection(Connection connection, String projectName, RunProjection source)
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
                insert.setNull(10, java.sql.Types.VARCHAR);
            } else {
                insert.setString(10, source.reasonCode());
            }
            insertNullable(insert, 11, source.executionProfile());
            insertNullable(insert, 12, source.suiteRunId());
            insertNullable(insert, 13, source.activePhase());
            insertNullable(insert, 14, boundedJson(source.artifact()));
            insert.setString(15, source.runDirPathRel());
            insert.executeUpdate();
        }
    }

    private void insertStateSurfaces(Connection connection, String projectName, RunProjection source)
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
            String encoded = boundedJson(continuation);
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

    private void insertState(
            Connection connection,
            String table,
            String projectName,
            RunProjection source,
            JsonNode value) throws SQLException {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        String encoded = boundedJson(value);
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

    private String boundedJson(JsonNode value) throws SQLException {
        try {
            String encoded = mapper.writeValueAsString(ArtifactRedactionPolicy.sanitizeJson(value));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new SQLException("state surface exceeds the persistence limit");
            }
            return encoded;
        } catch (IOException exception) {
            throw new SQLException("state surface could not be encoded", exception);
        }
    }

    private static JsonNode firstPresent(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private List<RunProjection> scanRunArtifacts(Path databasePath) {
        Path projectDirectory = databasePath.getParent();
        Path workspaceRoot = projectDirectory.getParent().getParent();
        String projectName = projectDirectory.getFileName().toString();
        ArtifactPathPolicy policy = new ArtifactPathPolicy(workspaceRoot);
        List<RunProjection> projections = new ArrayList<>();
        for (String suite : List.of("regression", "performance", "security")) {
            Path plans = policy.resolve(".mcpjvm", projectName, "plans", suite);
            for (Path plan : safeDirectories(plans, policy)) {
                Path runs = policy.check(plan.resolve("runs"));
                for (Path run : safeDirectories(runs, policy)) {
                    if (projections.size() >= MAX_SCAN_ENTRIES) {
                        throw new ArtifactOperationException(
                                "state_store_scan_limit_exceeded", "Run Artifact scan exceeds the bounded limit");
                    }
                    Path executionResult = policy.check(run.resolve("execution.result.json"));
                    Path legacyResult = policy.check(run.resolve("result.json"));
                    Path artifact = Files.isRegularFile(executionResult) ? executionResult : legacyResult;
                    projections.add(readProjection(
                            suite,
                            plan.getFileName().toString(),
                            run.getFileName().toString(),
                            artifact,
                            relativeToWorkspace(databasePath, run),
                            policy));
                }
            }
        }
        return projections;
    }

    private static List<Path> safeDirectories(Path root, ArtifactPathPolicy policy) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            List<Path> values = stream.filter(Files::isDirectory)
                    .map(path -> policy.check(path))
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_SCAN_ENTRIES + 1L)
                    .toList();
            if (values.size() > MAX_SCAN_ENTRIES) {
                throw new ArtifactOperationException(
                        "state_store_scan_limit_exceeded", "Artifact directory scan exceeds the bounded limit");
            }
            return values;
        } catch (IOException exception) {
            throw new ArtifactOperationException("state_store_scan_failed", "Run Artifacts could not be scanned");
        }
    }

    private RunProjection readProjection(
            String suiteType,
            String planName,
            String runId,
            Path artifact,
            String relativePath,
            ArtifactPathPolicy policy) {
        if (!Files.isRegularFile(artifact)) {
            return RunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_missing");
        }
        try {
            JsonNode root = readBoundedJson(artifact);
            if (root == null || !root.isObject()) {
                return RunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_invalid");
            }
            root = includeSidecarState(artifact.getParent(), root, policy);
            JsonNode steps = root.path("steps");
            int stepCount = steps.isArray() ? steps.size() : 0;
            int failedStepCount = 0;
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    String status = step.path("status").asText("");
                    if (!List.of("passed", "pass", "ok", "skipped_condition_false").contains(status)) {
                        failedStepCount++;
                    }
                }
            }
            return new RunProjection(
                    suiteType,
                    planName,
                    runId,
                    root.path("status").asText("blocked"),
                    stepCount,
                    failedStepCount,
                    epoch(root.get("startedAt")),
                    epoch(root.get("endedAt")),
                    root.path("reasonCode").asText(null),
                    root.path("executionProfile").asText(null),
                    root.path("suiteRunId").asText(null),
                    root.path("activePhase").asText(null),
                    relativePath,
                    root,
                    true,
                    "");
        } catch (IOException | RuntimeException exception) {
            return RunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_invalid");
        }
    }

    private JsonNode readBoundedJson(Path path) throws IOException {
        if (Files.size(path) > 4L * 1024L * 1024L) {
            throw new IOException("run Artifact exceeds the read limit");
        }
        return mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private JsonNode includeSidecarState(Path runDirectory, JsonNode root, ArtifactPathPolicy policy)
            throws IOException {
        ObjectNode enriched = (ObjectNode) root.deepCopy();
        for (String sidecar : List.of("continuation.json", "correlation.json", "watchers.json", "external-verification.json")) {
            Path path = policy.check(runDirectory.resolve(sidecar));
            if (Files.isRegularFile(path)) {
                String field = switch (sidecar) {
                    case "continuation.json" -> "continuation";
                    case "correlation.json" -> "correlations";
                    case "watchers.json" -> "watchers";
                    default -> "externalVerification";
                };
                enriched.set(field, readBoundedJson(path));
            }
        }
        return enriched;
    }

    private static List<Path> findFiles(Path root, String fileName) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path)
                            && fileName.equals(path.getFileName().toString()))
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_SCAN_ENTRIES + 1L)
                    .toList();
            if (files.size() > MAX_SCAN_ENTRIES) {
                throw new ArtifactOperationException(
                        "state_store_scan_limit_exceeded", "Legacy Artifact scan exceeds the bounded limit");
            }
            return files;
        } catch (IOException exception) {
            throw new ArtifactOperationException("state_store_scan_failed", "Run Artifacts could not be scanned");
        }
    }

    private static LegacyCorrelation legacyCorrelation(Path correlation) {
        Path run = correlation.getParent();
        Path runs = run == null ? null : run.getParent();
        Path plan = runs == null ? null : runs.getParent();
        if (run == null || plan == null || run.getFileName() == null || plan.getFileName() == null) {
            throw new ArtifactOperationException(
                    "state_store_backfill_source_invalid", "Legacy correlation path is not a plan run Artifact");
        }
        return new LegacyCorrelation(plan.getFileName().toString(), run.getFileName().toString());
    }

    private record LegacyCorrelation(String planName, String runId) {
    }

    private static String relativeToWorkspace(Path databasePath, Path path) {
        Path workspace = databasePath.getParent().getParent().getParent();
        return workspace.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static Long epoch(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText()).toEpochMilli();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void insertNullable(
            java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<Map<String, Object>> readRows(
            Connection connection, QueryPlan queryPlan, int pageSize, int offset) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(queryPlan.selectSql())) {
            bind(statement, queryPlan.parameters(), pageSize + 1, offset);
            try (ResultSet result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index), result.getObject(index));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static JsonNode firstNode(JsonNode query, JsonNode filters, String field) {
        JsonNode value = filters == null ? null : filters.get(field);
        return value == null || value.isNull() ? query.get(field) : value;
    }

    private static QueryPlan queryPlan(String projectName, String table, JsonNode input) {
        JsonNode query = input == null || !input.isObject()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : input;
        QueryContext context = new QueryContext(table, query, query.path("filters"),
                new ArrayList<>(), new ArrayList<>());
        context.predicates().add("project_name = ?");
        context.parameters().add(projectName);
        addQueryFilters(context);
        String where = String.join(" AND ", context.predicates());
        return new QueryPlan(
                "SELECT * FROM " + table + " WHERE " + where + queryOrder(context) + " LIMIT ? OFFSET ?",
                "SELECT COUNT(*) FROM " + table + " WHERE " + where,
                context.parameters());
    }

    private static void addQueryFilters(QueryContext context) {
        addColumnFilter(context, "planName", "plan_name");
        addColumnFilter(context, "runId", "run_id");
        addColumnFilter(context, "suiteRunId", "suite_run_id");
        addColumnFilter(context, "suiteType", "suite_type");
        addColumnFilter(context, "executionProfile", "execution_profile");
        addColumnFilter(context, "reasonCode", "reason_code");
        addColumnFilter(context, "activePhase", "active_phase");

        JsonNode status = firstNode(context.query(), context.filters(), "status");
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

    private static String queryOrder(QueryContext context) {
        String direction = sortDirection(context.query());
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

    private static void addColumnFilter(QueryContext context, String inputField, String column) {
        JsonNode expected = firstNode(context.query(), context.filters(), inputField);
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

    private static void addValueFilter(QueryContext context, String column, JsonNode expected) {
        List<String> values = textValues(expected);
        if (values.isEmpty()) {
            context.predicates().add("1 = 0");
            return;
        }
        context.predicates().add(column + " IN (" + placeholders(values.size()) + ")");
        context.parameters().addAll(values);
    }

    private static void addStateValueFilter(QueryContext context, String field, JsonNode expected) {
        List<String> values = textValues(expected);
        if (values.isEmpty()) {
            context.predicates().add("1 = 0");
            return;
        }
        context.predicates().add("EXISTS (SELECT 1 FROM json_tree(COALESCE(state_json, '{}')) AS state "
                + "WHERE state.key = ? AND CAST(state.value AS TEXT) IN ("
                + placeholders(values.size()) + "))");
        context.parameters().add(field);
        context.parameters().addAll(values);
    }

    private static void addRangeFilter(
            QueryContext context, String inputField, String column, boolean minimum) {
        JsonNode value = firstNode(context.query(), context.filters(), inputField);
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

    private static String stateRangeField(String inputField) {
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

    private static List<String> textValues(JsonNode value) {
        List<String> values = new ArrayList<>();
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            values.add(value.asText());
        } else if (value != null && value.isArray()) {
            for (JsonNode child : value) {
                if (child.isTextual() && !child.asText().isBlank()) {
                    values.add(child.asText());
                }
            }
        }
        return values;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static int countRows(Connection connection, QueryPlan queryPlan) throws SQLException {
        try (var statement = connection.prepareStatement(queryPlan.countSql())) {
            bind(statement, queryPlan.parameters(), null, null);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static void bind(
            java.sql.PreparedStatement statement,
            List<Object> parameters,
            Integer limit,
            Integer offset) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
        if (limit != null) {
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
        }
    }

    private record QueryContext(
            String table,
            JsonNode query,
            JsonNode filters,
            List<String> predicates,
            List<Object> parameters) {
    }

    private record QueryPlan(String selectSql, String countSql, List<Object> parameters) {
    }

    private static String sortDirection(JsonNode query) {
        if (query == null || query.isNull()) {
            return "desc";
        }
        return query.path("sortDirection").asText(query.path("sort").path("direction").asText("desc"));
    }

    private static int pageSize(JsonNode query) {
        int value = query == null ? 10 : query.path("pageSize")
                .asInt(query.path("page").path("pageSize").asInt(10));
        if (value < 1 || value > 100) {
            throw new ArtifactOperationException("run_state_page_invalid", "pageSize must be between 1 and 100");
        }
        return value;
    }

    private static int cursorOffset(JsonNode query) {
        if (query == null) {
            return 0;
        }
        String cursor = query.path("cursor").asText(query.path("page").path("cursor").asText(""));
        if (cursor.isBlank()) {
            return 0;
        }
        try {
            JsonNode decoded = new ObjectMapper().readTree(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            int offset = decoded.path("offset").asInt(-1);
            if (offset < 0 || !sortDirection(query).equals(decoded.path("direction").asText(""))) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (RuntimeException | IOException exception) {
            throw new ArtifactOperationException("run_state_cursor_invalid", "run-state cursor is invalid");
        }
    }

    private static String encodeCursor(int offset, String direction) {
        String payload = "{\"offset\":" + offset + ",\"direction\":\"" + direction + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> publicRow(Map<String, Object> row, JsonNode query) {
        Map<String, Object> output = new LinkedHashMap<>();
        putIfPresent(output, "stateKind", row.get("state_kind"));
        putIfPresent(output, "projectName", row.get("project_name"));
        putIfPresent(output, "suiteType", row.get("suite_type"));
        putIfPresent(output, "planName", row.get("plan_name"));
        putIfPresent(output, "runId", row.get("run_id"));
        putIfPresent(output, "suiteRunId", row.get("suite_run_id"));
        putIfPresent(output, "status", row.get("status"));
        putIfPresent(output, "executionProfile", row.get("execution_profile"));
        putIfPresent(output, "activePhase", row.get("active_phase"));
        putIfPresent(output, "stepCount", row.get("step_count"));
        putIfPresent(output, "failedStepCount", row.get("failed_step_count"));
        putIfPresent(output, "startedAtEpochMs", row.get("started_at_epoch_ms"));
        putIfPresent(output, "completedAtEpochMs", row.get("completed_at_epoch_ms"));
        putIfPresent(output, "reasonCode", row.get("reason_code"));
        Object completed = row.get("completed_at_epoch_ms");
        Object started = row.get("started_at_epoch_ms");
        output.put("updatedAtEpochMs", completed instanceof Number ? completed : started);
        putIfPresent(output, "runDirPathRel", row.get("run_dir_path_rel"));
        if (row.get("state_json") != null && !output.containsKey("status")) {
            try {
                output.put("state", mapper.readTree(String.valueOf(row.get("state_json"))));
            } catch (IOException exception) {
                throw new ArtifactOperationException("run_state_detail_invalid", "run-state detail is invalid");
            }
        }
        addDetails(output, row, query);
        return output;
    }

    private void addDetails(Map<String, Object> output, Map<String, Object> row, JsonNode query) {
        JsonNode detail = query == null ? null : query.path("detail");
        boolean hasDetail = detail != null && detail.isObject() && detail.size() > 0;
        boolean hasWindows = query != null && (query.path("watchers").isObject()
                || query.path("watcherEvidence").isObject());
        if (!hasDetail && !hasWindows) {
            return;
        }
        try {
            JsonNode state = row.get("state_json") == null
                    ? mapper.createObjectNode() : mapper.readTree(String.valueOf(row.get("state_json")));
            Map<String, Object> selected = selectedDetails(state, detail, hasDetail);
            if (hasDetail) {
                output.put("detail", ArtifactRedactionPolicy.sanitizeMap(selected));
            }
            addStateWindows(output, state, query);
        } catch (IOException exception) {
            throw new ArtifactOperationException("run_state_detail_invalid", "run-state detail is invalid");
        }
    }

    private static Map<String, Object> selectedDetails(
            JsonNode state, JsonNode detail, boolean hasDetail) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (!hasDetail) {
            return selected;
        }
        for (String field : List.of("continuation", "observations", "assertions", "ownerLease")) {
            if (detail.path(field).asBoolean(false)) {
                selected.put(field, state.get(field));
            }
        }
        for (String field : List.of("keys", "lineExpectations", "probeObservations", "attempts")) {
            addWindow(selected, state, detail, field);
        }
        if (detail.path("select").isArray()) {
            for (JsonNode selector : detail.path("select")) {
                if (selector.isTextual() && state.has(selector.asText())) {
                    selected.put(selector.asText(), state.get(selector.asText()));
                }
            }
        }
        return selected;
    }

    private static void addStateWindows(Map<String, Object> output, JsonNode state, JsonNode query) {
        if (query.path("watchers").isObject()) {
            JsonNode watchers = window(state.get("watchers"), query.path("watchers"));
            output.put("watchers", filterWatchers(watchers, query.path("watcherFilter")));
        }
        if (query.path("watcherEvidence").isObject()) {
            output.put("watcherEvidence", window(state.get("watcherEvidence"), query.path("watcherEvidence")));
        }
    }

    private static void addWindow(
            Map<String, Object> selected, JsonNode state, JsonNode detail, String field) {
        if (detail.path(field).isObject()) {
            selected.put(field, window(state.get(field), detail.path(field)));
        }
    }

    private static JsonNode window(JsonNode value, JsonNode options) {
        if (value == null || !value.isArray()) {
            return value;
        }
        int offset = Math.max(0, options.path("offset").asInt(0));
        int limit = Math.min(250, Math.max(1, options.path("limit").asInt(25)));
        var output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (int index = offset; index < Math.min(value.size(), offset + limit); index++) {
            output.add(value.get(index));
        }
        return output;
    }

    private static JsonNode filterWatchers(JsonNode value, JsonNode filter) {
        if (filter == null || !filter.isObject() || value == null || !value.isArray()) {
            return value;
        }
        String watcherId = filter.path("watcherId").asText(null);
        String status = filter.path("watcherStatus").asText(null);
        var output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode watcher : value) {
            if (watcherId != null && !watcherId.equals(watcher.path("watcherId").asText(null))) {
                continue;
            }
            if (status != null && !status.equals(watcher.path("status").asText(null))) {
                continue;
            }
            output.add(watcher);
        }
        return output;
    }

    private static void putIfPresent(Map<String, Object> output, String name, Object value) {
        if (value != null) {
            output.put(name, value);
        }
    }

    private static String tableFor(String stateSurface) {
        return switch (stateSurface == null ? "run_state" : stateSurface) {
            case "run_state" -> "plan_runs";
            case "correlation_state" -> "correlation_state";
            case "watcher_state" -> "watcher_state";
            case "external_verification_state" -> "external_verification_state";
            default -> throw new ArtifactOperationException(
                    "state_surface_invalid", "stateSurface is unsupported");
        };
    }

    private static StoreLock acquireLock(Path databasePath) {
        Path lockPath = databasePath.resolveSibling(databasePath.getFileName() + ".lock");
        try {
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new ArtifactOperationException(
                            "state_store_busy", "SQLite state store is locked by another operation");
                }
                return new StoreLock(channel, lock);
            } catch (OverlappingFileLockException exception) {
                channel.close();
                throw new ArtifactOperationException(
                        "state_store_busy", "SQLite state store is locked by another operation");
            }
        } catch (IOException exception) {
            throw new ArtifactOperationException(
                    "state_store_lock_failed", "SQLite state-store lock could not be acquired");
        }
    }

    private static void writeAtomicMarker(Path marker, String projectName) throws IOException {
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

    private record StoreLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() {
            try {
                lock.release();
                channel.close();
            } catch (IOException exception) {
                throw new ArtifactOperationException(
                        "state_store_lock_release_failed", "SQLite state-store lock could not be released");
            }
        }
    }

    private record RunProjection(
            String suiteType,
            String planName,
            String runId,
            String status,
            int stepCount,
            int failedStepCount,
            Long startedAtEpochMs,
            Long completedAtEpochMs,
            String reasonCode,
            String executionProfile,
            String suiteRunId,
            String activePhase,
            String runDirPathRel,
            JsonNode artifact,
            boolean valid,
            String reason) {

        private static RunProjection invalid(String suiteType, String planName, String runId, String path, String reason) {
            return new RunProjection(suiteType, planName, runId, "blocked", 0, 0,
                    null, null, reason, null, null, null, path, null, false, reason);
        }
    }

    private record RebuildCounts(int scanned, int rebuilt, int invalid) {
    }
}
