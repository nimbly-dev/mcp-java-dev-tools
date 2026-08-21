package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts credentials from HTTP headers, URLs, and response previews. */
public final class HttpSensitiveDataRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "\\b(Bearer|Basic)\\s+[^\\s,;]+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "([\\\"']?)([A-Za-z0-9_.-]+)\\1(\\s*[:=]\\s*)"
                    + "(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"?|" 
                    + "'(?:\\\\.|[^'\\\\])*'?|[^\\s,;}\\]]+)");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Redacts sensitive values while retaining safe response header names. */
    public Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            result.put(name, isSensitiveName(name) ? REDACTED : entry.getValue());
        }
        return Map.copyOf(result);
    }

    /** Redacts sensitive values in a body preview without exposing client details. */
    public String redactPreview(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node != null) {
                return redactJson(node).toString();
            }
        } catch (Exception ignored) {
            // Text fallback below is deterministic and bounded by the caller.
        }
        return redactText(value);
    }

    /** Retains safe query keys while replacing values for sensitive keys. */
    public String redactUri(URI uri) {
        if (uri == null) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        value.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        if (uri.getRawPath() != null) {
            value.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            value.append('?').append(redactQuery(uri.getRawQuery()));
        }
        return value.toString();
    }

    /** @return true when a header or JSON field name is sensitive */
    public boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        String compact = normalized.replace("-", "").replace("_", "");
        return SENSITIVE_HEADERS.contains(normalized)
                || compact.equals("apikey")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential");
    }

    private JsonNode redactJson(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), isSensitiveName(field.getKey())
                        ? OBJECT_MAPPER.getNodeFactory().textNode(REDACTED)
                        : redactJson(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int index = 0; index < copy.size(); index++) {
                copy.set(index, redactJson(copy.get(index)));
            }
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(redactText(node.asText()));
        }
        return node;
    }

    /** Redacts credentials from arbitrary text, embedded JSON, and malformed assignments. */
    public String redactText(String value) {
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1 " + REDACTED);
        Matcher assignments = ASSIGNMENT.matcher(redacted);
        StringBuffer output = new StringBuffer();
        while (assignments.find()) {
            if (!isSensitiveName(assignments.group(2))) {
                continue;
            }
            String rawValue = assignments.group(4);
            char quote = rawValue.isEmpty() ? 0 : rawValue.charAt(0);
            String safeValue = quote == '\"' || quote == '\''
                    ? quote + REDACTED + quote
                    : REDACTED;
            String replacement = assignments.group(1) + assignments.group(2)
                    + assignments.group(1) + assignments.group(3) + safeValue;
            assignments.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        assignments.appendTail(output);
        return output.toString();
    }

    private String redactQuery(String rawQuery) {
        String[] parts = rawQuery.split("&", -1);
        for (int index = 0; index < parts.length; index++) {
            String[] pair = parts[index].split("=", 2);
            String key;
            try {
                key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                key = pair[0];
            }
            if (isSensitiveName(key)) {
                parts[index] = pair[0] + "=" + REDACTED;
            }
        }
        return String.join("&", parts);
    }
}
