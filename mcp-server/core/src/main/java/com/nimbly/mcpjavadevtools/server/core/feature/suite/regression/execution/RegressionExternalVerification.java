package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes bounded HTTP or connection-reference SQL verification after Watchers. */
final class RegressionExternalVerification {

    private static final int MAX_ROWS = 1_000;
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_RESPONSE_CHARS = 256_000;
    private static final Pattern SQL_TOKEN = Pattern.compile(
            "(?<block>/\\*)|--[^\\r\\n]*|"
                    + "(?i:\\bE)'(''|\\\\.|[^'\\\\])*+'|"
                    + "'(''|[^'])*+'|\"(\"\"|[^\"])*+\"|`(``|[^`])*+`|\\[[^]]*]|"
                    + "(?<delimiter>\\$([A-Za-z_][A-Za-z0-9_]*)?\\$).*?\\k<delimiter>|"
                    + "::|:(?<parameter>[A-Za-z_][A-Za-z0-9_]*)",
            Pattern.DOTALL);

    private final RegressionVerificationSupport support;

    RegressionExternalVerification(RegressionVerificationSupport support) {
        this.support = support;
    }

    Result execute(JsonNode input, Map<String, Object> context) {
        JsonNode verifications = input.path("contract").path("externalVerification");
        if (!verifications.isArray() || verifications.isEmpty()) {
            return Result.notConfigured();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode verification : verifications) {
            String provider = verification.path("provider").path("type").asText();
            VerificationResult result = "http".equals(provider)
                    ? executeHttp(verification, context)
                    : executeSql(verification, input, context);
            results.add(row(verification, provider, result));
            if (!result.success()) {
                return Result.failure(result.reasonCode(), results);
            }
            String expectationFailure = support.firstEnvelopeExpectationFailure(
                    verification.path("expect"), result.envelope(), context);
            if (expectationFailure != null) {
                results.set(results.size() - 1,
                        row(verification, provider,
                                VerificationResult.failure("external_verification_expectation_failed")));
                return Result.failure("external_verification_expectation_failed", results);
            }
            String extractionFailure = extract(verification.path("extract"), result.envelope(), context);
            if (extractionFailure != null) {
                return Result.failure(extractionFailure, results);
            }
        }
        return Result.success(results);
    }

    private VerificationResult executeHttp(JsonNode verification, Map<String, Object> context) {
        RegressionVerificationSupport.Observation observation = support.executeHttp(
                verification.path("request").path("http"), context);
        if (!observation.success()) {
            return VerificationResult.failure("external_verification_transport_failed");
        }
        return VerificationResult.success(Map.of("response", observation.response()));
    }

    private VerificationResult executeSql(
            JsonNode verification,
            JsonNode input,
            Map<String, Object> context) {
        JsonNode request = verification.path("request").path("sql");
        String reference = request.path("connectionRef").asText();
        ConnectionDetails connection = connection(input, context, reference);
        if (connection.reasonCode() != null) {
            return VerificationResult.failure(connection.reasonCode());
        }
        String statement = request.path("statement").asText();
        if (statement.isBlank()) {
            return VerificationResult.failure("external_verification_request_invalid");
        }
        BoundStatement bound = bindStatement(statement, request.path("parameters"), context);
        if (bound.reasonCode() != null) {
            return VerificationResult.failure(bound.reasonCode());
        }
        long startedAt = System.currentTimeMillis();
        try (Connection jdbc = open(connection);
                PreparedStatement query = jdbc.prepareStatement(bound.statement())) {
            int timeoutMs = bounded(request.path("timeoutMs").asInt(5_000), 1, 60_000);
            query.setQueryTimeout(Math.max(1, (int) Math.ceil(timeoutMs / 1_000.0)));
            bind(query, bound.values());
            try (ResultSet resultSet = query.executeQuery()) {
                List<Map<String, Object>> rows = rows(resultSet);
                Map<String, Object> sql = new LinkedHashMap<>();
                sql.put("rowCount", rows.size());
                sql.put("rows", rows);
                sql.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
                if (!rows.isEmpty()) {
                    sql.put("firstRow", rows.getFirst());
                }
                return VerificationResult.success(Map.of("sql", Map.copyOf(sql)));
            }
        } catch (SQLException exception) {
            return VerificationResult.failure("external_verification_execution_failed");
        }
    }

    private Connection open(ConnectionDetails details) throws SQLException {
        if (details.properties().isEmpty()) {
            return DriverManager.getConnection(details.jdbcUrl());
        }
        return DriverManager.getConnection(details.jdbcUrl(), details.properties());
    }

    private void bind(PreparedStatement query, List<Object> values) throws SQLException {
        int index = 1;
        for (Object value : values) {
            query.setObject(index++, value);
        }
    }

    private BoundStatement bindStatement(
            String statement,
            JsonNode parameters,
            Map<String, Object> context) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (JsonNode parameter : parameters) {
            String name = text(parameter.path("name"));
            if (name == null || values.containsKey(name)) {
                return BoundStatement.failure("external_verification_request_unresolved");
            }
            if (parameter.has("valueFromContext")) {
                String key = text(parameter.path("valueFromContext"));
                if (key == null || !context.containsKey(key)) {
                    return BoundStatement.failure("external_verification_request_unresolved");
                }
                values.put(name, context.get(key));
            } else {
                values.put(name, parameter.has("value")
                        ? scalar(parameter.get("value")) : null);
            }
        }
        return bindSqlTokens(statement, values);
    }

    private BoundStatement bindSqlTokens(String statement, Map<String, Object> values) {
        Matcher matcher = SQL_TOKEN.matcher(statement);
        StringBuilder positional = new StringBuilder();
        List<Object> ordered = new ArrayList<>();
        int copiedTo = 0;
        while (matcher.find()) {
            if (matcher.group("block") != null) {
                matcher.region(blockCommentEnd(statement, matcher.end()), statement.length());
                continue;
            }
            String name = matcher.group("parameter");
            if (name == null) {
                continue;
            }
            if (!values.containsKey(name)) {
                return BoundStatement.failure("external_verification_request_unresolved");
            }
            positional.append(statement, copiedTo, matcher.start()).append('?');
            copiedTo = matcher.end();
            ordered.add(values.get(name));
        }
        positional.append(statement, copiedTo, statement.length());
        return BoundStatement.success(positional.toString(), ordered);
    }

    private int blockCommentEnd(String statement, int start) {
        int depth = 1;
        int cursor = start;
        while (depth > 0 && cursor < statement.length()) {
            int closing = statement.indexOf("*/", cursor);
            if (closing < 0) {
                return statement.length();
            }
            int opening = statement.indexOf("/*", cursor);
            if (opening >= 0 && opening < closing) {
                depth++;
                cursor = opening + 2;
            } else {
                depth--;
                cursor = closing + 2;
            }
        }
        return cursor;
    }

    private List<Map<String, Object>> rows(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columns = Math.min(metadata.getColumnCount(), MAX_COLUMNS);
        int responseChars = 0;
        while (resultSet.next()) {
            if (rows.size() >= MAX_ROWS) {
                throw new SQLException("result_limit_exceeded");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= columns; column++) {
                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            responseChars += row.toString().length();
            if (responseChars > MAX_RESPONSE_CHARS) {
                throw new SQLException("result_size_limit_exceeded");
            }
            rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return List.copyOf(rows);
    }

    private ConnectionDetails connection(
            JsonNode input,
            Map<String, Object> context,
            String reference) {
        String prefix = "sql.connection." + reference;
        if (reference.isBlank() || context.keySet().stream().anyMatch(key -> key.startsWith(prefix + ".jdbc."))) {
            return ConnectionDetails.failure("external_verification_connection_unsupported");
        }
        String kind = text(context.get(prefix + ".kind"));
        if ("sqlite".equals(kind)) {
            String filePath = text(context.get(prefix + ".sqlite.filePath"));
            if (filePath == null) {
                return ConnectionDetails.failure("external_verification_connection_unresolved");
            }
            Path path = Path.of(filePath);
            if (!path.isAbsolute()) {
                String workspace = text(input.path("workspaceRootAbs"));
                if (workspace == null) {
                    return ConnectionDetails.failure("external_verification_connection_unresolved");
                }
                path = Path.of(workspace).resolve(path);
            }
            return ConnectionDetails.success("jdbc:sqlite:" + path.toAbsolutePath().normalize(), new Properties());
        }
        if (!"postgresql".equals(kind)) {
            return ConnectionDetails.failure(kind == null
                    ? "external_verification_connection_unresolved"
                    : "external_verification_connection_unsupported");
        }
        return postgresqlConnection(context, prefix);
    }

    private ConnectionDetails postgresqlConnection(Map<String, Object> context, String prefix) {
        String host = text(context.get(prefix + ".host"));
        String database = text(context.get(prefix + ".database"));
        String username = text(context.get(prefix + ".username"));
        String password = text(context.get(prefix + ".password"));
        String tlsMode = text(context.get(prefix + ".tls.mode"));
        Integer port = integer(context.get(prefix + ".port"));
        if (host == null || database == null || username == null || password == null
                || port == null || port < 1 || port > 65_535
                || !("disable".equals(tlsMode) || "require".equals(tlsMode) || "verify-full".equals(tlsMode))) {
            return ConnectionDetails.failure("external_verification_connection_invalid");
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", tlsMode);
        return ConnectionDetails.success(
                "jdbc:postgresql://" + host + ":" + port + "/" + database, properties);
    }

    private String extract(
            JsonNode extractions,
            Map<String, Object> envelope,
            Map<String, Object> context) {
        for (JsonNode extraction : extractions) {
            Object value = RegressionStepExpectationEvaluator.read(
                    envelope, extraction.path("from").asText());
            if (value == null && extraction.path("required").asBoolean()) {
                return "external_verification_extract_missing";
            }
            if (value != null && !extraction.path("as").asText().isBlank()) {
                context.put(extraction.path("as").asText(), value);
            }
        }
        return null;
    }

    private Map<String, Object> row(
            JsonNode verification,
            String provider,
            VerificationResult result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", verification.path("id").asText());
        row.put("provider", provider);
        row.put("status", result.success() ? "pass" : "blocked_runtime");
        row.put("reasonCode", result.reasonCode());
        if (result.success()) {
            row.putAll(result.envelope());
        }
        return Map.copyOf(row);
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static Object scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.doubleValue();
        }
        return value.isBoolean() ? value.booleanValue() : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value instanceof String text ? Integer.valueOf(text) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record Result(boolean configured, boolean success, String reasonCode, List<Map<String, Object>> details) {
        private static Result notConfigured() {
            return new Result(false, true, "ok", List.of());
        }

        private static Result success(List<Map<String, Object>> details) {
            return new Result(true, true, "ok", List.copyOf(details));
        }

        private static Result failure(String reasonCode, List<Map<String, Object>> details) {
            return new Result(true, false, reasonCode, List.copyOf(details));
        }
    }

    private record VerificationResult(boolean success, String reasonCode, Map<String, Object> envelope) {
        private static VerificationResult success(Map<String, Object> envelope) {
            return new VerificationResult(true, "ok", Map.copyOf(envelope));
        }

        private static VerificationResult failure(String reasonCode) {
            return new VerificationResult(false, reasonCode, Map.of());
        }
    }

    private record BoundStatement(String statement, List<Object> values, String reasonCode) {
        private static BoundStatement success(String statement, List<Object> values) {
            return new BoundStatement(statement, Collections.unmodifiableList(new ArrayList<>(values)), null);
        }

        private static BoundStatement failure(String reasonCode) {
            return new BoundStatement(null, List.of(), reasonCode);
        }
    }

    private record ConnectionDetails(String jdbcUrl, Properties properties, String reasonCode) {
        private static ConnectionDetails success(String jdbcUrl, Properties properties) {
            return new ConnectionDetails(jdbcUrl, properties, null);
        }

        private static ConnectionDetails failure(String reasonCode) {
            return new ConnectionDetails(null, new Properties(), reasonCode);
        }
    }
}
