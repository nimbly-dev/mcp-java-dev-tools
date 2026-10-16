package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Prepares bounded HTTP requests and evaluates their declared response expectations. */
class SecurityRequestExecution {

    private static final java.util.regex.Pattern PATH_PARAMETER = java.util.regex.Pattern.compile(
            "%7B([^%/]+)%7D", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CONTEXT_EXPRESSION = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
    private final TransportExecutionFeature transport;

    SecurityRequestExecution(TransportExecutionFeature transport) {
        this.transport = transport;
    }

    ExecuteTransportResult executeRequest(
            URI requestUri, JsonNode entrypoint, JsonNode request, JsonNode profile, SecurityRequestContext credentials) {
        return executeRequest(requestUri, entrypoint, request, profile, credentials, true);
    }

    ExecuteTransportResult executeRequest(
            URI requestUri,
            JsonNode entrypoint,
            JsonNode request,
            JsonNode profile,
            SecurityRequestContext credentials,
            boolean applyCredentials) {
        try {
            Map<String, Object> payload = requestPayload(requestUri, entrypoint, request, profile, credentials, applyCredentials);
            if (!allowed(credentials.boundary(), URI.create(String.valueOf(payload.get("url"))))) {
                return ExecuteTransportResult.blockedInvalid("security_target_boundary_violation",
                        "Keep resolved requests inside the declared target boundary", "http", 1);
            }
            return credentials.budget().execute(transport, payload);
        } catch (IllegalArgumentException exception) {
            return ExecuteTransportResult.blockedInvalid("security_blackbox_fixture_unresolved",
                    "Resolve every declared fixture expression and path parameter before execution", "http", 1);
        }
    }

    private static Map<String, Object> requestPayload(
            URI requestUri, JsonNode entrypoint, JsonNode request, JsonNode profile, SecurityRequestContext credentials, boolean applyCredentials) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> fixtures = credentials.fixtureContext(entrypoint);
        JsonNode value = credentials.resolveRequest(request, fixtures);
        String path = resolveTransportPath(transportDefinition(entrypoint).path("path").asText(), fixtures);
        URI resolved = withPathParameters(uri(requestUri, path), value);
        if (resolved == null) {
            throw new IllegalArgumentException("unresolved Security path");
        }
        payload.put("url", withQuery(resolved, query(value)).toString());
        payload.put("method", transportDefinition(entrypoint).path("method").asText());
        Map<String, String> headers = SecurityRequestContext.requestHeaders(value.path("headers"));
        if (applyCredentials) {
            credentials.apply(profile, headers);
        }
        if (!headers.isEmpty()) {
            payload.put("headers", Map.copyOf(headers));
        }
        if (!value.path("body").isMissingNode() && !value.path("body").isNull()) {
            JsonNode body = value.path("body");
            payload.put("body", body.isTextual() ? body.asText() : body.toString());
        }
        return payload;
    }

    private static String resolveTransportPath(String template, Map<String, String> context) {
        return CONTEXT_EXPRESSION.matcher(template).replaceAll(match -> {
            String value = SecurityRequestContext.resolveString(match.group(), context);
            try {
                // Encode fixture data as path content, preserving the template's existing URI escapes.
                String encoded = new URI(null, null, "/" + value, null).toASCIIString().substring(1);
                return java.util.regex.Matcher.quoteReplacement(encoded);
            } catch (java.net.URISyntaxException exception) {
                throw new IllegalArgumentException("invalid Security transport path fixture");
            }
        });
    }

    private static URI withPathParameters(URI requestUri, JsonNode request) {
        if (requestUri == null) {
            return null;
        }
        JsonNode pathParameters = request.path("pathParameters");
        var placeholders = PATH_PARAMETER.matcher(requestUri.getRawPath());
        while (placeholders.find()) {
            if (!pathParameters.hasNonNull(placeholders.group(1))) {
                return null;
            }
        }
        if (!pathParameters.isObject() || pathParameters.isEmpty()) {
            return requestUri;
        }
        String path = requestUri.getRawPath();
        var fields = pathParameters.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String placeholder = "%7B" + entry.getKey() + "%7D";
            path = path.replace(placeholder, java.net.URLEncoder.encode(
                    entry.getValue().asText(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        }
        try {
            String query = requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery();
            return URI.create(requestUri.getScheme() + "://" + requestUri.getRawAuthority() + path + query);
        } catch (IllegalArgumentException exception) {
            return requestUri;
        }
    }

    private static URI withQuery(URI requestUri, JsonNode query) {
        if (!query.isObject() || query.isEmpty()) {
            return requestUri;
        }
        StringBuilder values = new StringBuilder();
        if (requestUri.getRawQuery() != null) {
            for (String pair : requestUri.getRawQuery().split("&")) {
                String name = java.net.URLDecoder.decode(pair.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
                if (!query.has(name)) {
                    if (!values.isEmpty()) {
                        values.append('&');
                    }
                    values.append(pair);
                }
            }
        }
        query.fields().forEachRemaining(entry -> appendQuery(values, entry.getKey(), entry.getValue().asText()));
        try {
            return URI.create(requestUri.getScheme() + "://" + requestUri.getRawAuthority()
                    + requestUri.getRawPath() + "?" + values);
        } catch (IllegalArgumentException exception) {
            return requestUri;
        }
    }

    private static void appendQuery(StringBuilder values, String name, String value) {
        if (!values.isEmpty()) {
            values.append('&');
        }
        values.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
        values.append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean matches(ExecuteTransportResult result, JsonNode expectation) {
        if (!transported(result)) {
            return false;
        }
        String outcome = expectation.path("outcome").asText();
        if (!expectedStatusCode(result.statusCode(), expectation.path("statusCodes"))) {
            return false;
        }
        return switch (outcome) {
            case "allow" -> allowedResponse(result);
            case "deny" -> deniedResponse(result);
            case "error" -> result.statusCode() != null && result.statusCode() >= 500;
            default -> false;
        };
    }

    private static boolean expectedStatusCode(Integer actual, JsonNode expected) {
        if (!expected.isArray() || expected.isEmpty()) {
            return true;
        }
        for (JsonNode value : expected) {
            if (actual != null && actual.intValue() == value.asInt()) {
                return true;
            }
        }
        return false;
    }

    static boolean allowedResponse(ExecuteTransportResult result) {
        return result.statusCode() != null && result.statusCode() >= 200 && result.statusCode() < 400;
    }

    static boolean transported(ExecuteTransportResult result) {
        return "pass".equals(result.status()) || "fail_http".equals(result.status());
    }

    static boolean deniedResponse(ExecuteTransportResult result) {
        return result.statusCode() != null && result.statusCode() >= 400 && result.statusCode() < 500;
    }

    private static JsonNode query(JsonNode request) {
        return request.path("query").isObject() ? request.path("query") : request.path("queryParameters");
    }

    static JsonNode transportDefinition(JsonNode entrypoint) {
        return entrypoint.path("transport").isObject() ? entrypoint.path("transport") : entrypoint;
    }

    static URI uri(URI baseUrl, String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        try {
            return baseUrl.resolve(path.replace("{", "%7B").replace("}", "%7D"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static boolean allowed(JsonNode boundary, URI value) {
        int port = port(value);
        return containsText(boundary.path("allowedHosts"), value.getHost())
                && containsPort(boundary.path("allowedPorts"), port);
    }

    private static int port(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equals(value.getScheme()) ? 443 : 80;
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPort(JsonNode values, int expected) {
        for (JsonNode value : values) {
            if (value.canConvertToInt() && value.asInt() == expected) {
                return true;
            }
        }
        return false;
    }
}
