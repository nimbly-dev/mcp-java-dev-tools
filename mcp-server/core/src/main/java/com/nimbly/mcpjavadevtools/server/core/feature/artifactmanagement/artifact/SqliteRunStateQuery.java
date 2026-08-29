package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the bounded SQLite query contract and assembles a deterministic page. */
final class SqliteRunStateQuery {

    private final ObjectMapper mapper;
    private final SqliteRunStateQueryPlanBuilder plans = new SqliteRunStateQueryPlanBuilder();
    private final SqliteRunStateQueryRows rows = new SqliteRunStateQueryRows();

    SqliteRunStateQuery(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> query(
            Path databasePath, String projectName, String stateSurface, JsonNode input) {
        if (!Files.isRegularFile(databasePath)) {
            return Map.of("source", "sqlite", "status", "unavailable", "items", List.of());
        }
        try (Connection connection = SqliteRunStateDatabase.open(databasePath)) {
            String table = SqliteRunStateQueryContract.tableFor(stateSurface);
            if (!SqliteRunStateDatabase.hasTable(connection, table)) {
                return Map.of("source", "sqlite", "status", "available", "items", List.of());
            }
            int pageSize = SqliteRunStateQueryContract.pageSize(input);
            int offset = SqliteRunStateQueryContract.cursorOffset(input);
            SqliteRunStateQueryPlan queryPlan = plans.build(projectName, table, input);
            List<Map<String, Object>> rows = this.rows.readRows(connection, queryPlan, pageSize, offset);
            if (rows.isEmpty() && offset > 0 && this.rows.countRows(connection, queryPlan) < offset) {
                throw new ArtifactOperationException("run_state_cursor_invalid", "run-state cursor is invalid");
            }
            boolean hasMore = rows.size() > pageSize;
            List<Map<String, Object>> page = new ArrayList<>();
            SqliteRunStateRowMapper rowMapper = new SqliteRunStateRowMapper(mapper);
            for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), pageSize))) {
                page.add(rowMapper.publicRow(row, input));
            }
            String direction = SqliteRunStateQueryContract.sortDirection(input);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("source", "sqlite");
            output.put("status", "available");
            output.put("stateSurface", stateSurface);
            output.put("projectName", projectName);
            output.put("projectionVersion", 1);
            output.put("strict", input != null && input.path("strict").asBoolean(false));
            output.put("pageSize", pageSize);
            output.put("hasMore", hasMore);
            output.put("sort", Map.of("field", "updatedAtEpochMs", "direction", direction));
            output.put("items", page);
            if (hasMore) {
                output.put("nextCursor", SqliteRunStateQueryContract.encodeCursor(offset + pageSize, direction));
            }
            return output;
        } catch (SQLException exception) {
            throw new ArtifactOperationException("state_store_corrupt", "SQLite state store could not be queried");
        }
    }
}
