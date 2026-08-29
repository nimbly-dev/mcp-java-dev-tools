package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes generated run-state SQL and materializes bounded JDBC rows. */
final class SqliteRunStateQueryRows {

    List<Map<String, Object>> readRows(
            Connection connection, SqliteRunStateQueryPlan queryPlan, int pageSize, int offset)
            throws SQLException {
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

    int countRows(Connection connection, SqliteRunStateQueryPlan queryPlan) throws SQLException {
        try (var statement = connection.prepareStatement(queryPlan.countSql())) {
            bind(statement, queryPlan.parameters(), null, null);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    void bind(
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
}
