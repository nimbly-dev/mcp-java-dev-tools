package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Validates bounded query pagination and maps public filters to query primitives. */
final class SqliteRunStateQueryContract {

    private SqliteRunStateQueryContract() {
    }

    static String tableFor(String stateSurface) {
        return switch (stateSurface == null ? "run_state" : stateSurface) {
            case "run_state" -> "plan_runs";
            case "correlation_state" -> "correlation_state";
            case "watcher_state" -> "watcher_state";
            case "external_verification_state" -> "external_verification_state";
            default -> throw new ArtifactOperationException(
                    "state_surface_invalid", "stateSurface is unsupported");
        };
    }

    static String sortDirection(JsonNode query) {
        if (query == null || query.isNull()) {
            return "desc";
        }
        return query.path("sortDirection").asText(query.path("sort").path("direction").asText("desc"));
    }

    static int pageSize(JsonNode query) {
        int value = query == null ? 10 : query.path("pageSize")
                .asInt(query.path("page").path("pageSize").asInt(10));
        if (value < 1 || value > 100) {
            throw new ArtifactOperationException("run_state_page_invalid", "pageSize must be between 1 and 100");
        }
        return value;
    }

    static int cursorOffset(JsonNode query) {
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

    static String encodeCursor(int offset, String direction) {
        String payload = "{\"offset\":" + offset + ",\"direction\":\"" + direction + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode firstNode(JsonNode query, JsonNode filters, String field) {
        JsonNode value = filters == null ? null : filters.get(field);
        return value == null || value.isNull() ? query.get(field) : value;
    }

    static List<String> textValues(JsonNode value) {
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

    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
