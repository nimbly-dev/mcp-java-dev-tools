package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.RouteSynthesisRecipeCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses bounded Actuator mapping responses into Core-owned runtime models. */
class RouteSynthesisRuntimeMappingsResponseReader {

    private static final String ATTEMPT = "spring_runtime_actuator_mappings";
    private static final Pattern HANDLER_PATTERN = Pattern.compile(
            "([A-Za-z_$][\\w.$]*)#([A-Za-z_$][\\w$]*)");
    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;

    RouteSynthesisRuntimeMappingsResponseReader(ObjectMapper objectMapper, int maxResponseBytes) {
        this.objectMapper = objectMapper;
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Reads one bounded HTTP response and returns sanitized Core evidence. */
    RouteSynthesisRuntimeMappingResolution read(
            HttpResponse<InputStream> response, URI endpoint, String classHint, String methodHint) {
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return failure("runtime_mappings_unauthorized", "runtime_mapping_fetch",
                        "authorize_runtime_mappings_access", List.of("httpStatus=" + response.statusCode()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure("runtime_mappings_unreachable", "runtime_mapping_fetch",
                        "verify_runtime_mappings_endpoint", endpointEvidence(endpoint));
            }
            String payload;
            try {
                payload = readBounded(body);
            } catch (ResponseTooLargeException exception) {
                payload = null;
            }
            if (payload == null) {
                return failure("runtime_mappings_response_too_large", "runtime_mapping_fetch",
                        "verify_runtime_mappings_payload", List.of(
                                "responseLimitBytes=" + maxResponseBytes));
            }
            return resolvePayload(payload, endpoint, classHint, methodHint);
        } catch (IOException exception) {
            return failure("runtime_mappings_unreachable", "runtime_mapping_fetch",
                    "verify_runtime_mappings_endpoint", endpointEvidence(endpoint));
        }
    }

    private RouteSynthesisRuntimeMappingResolution resolvePayload(
            String body, URI endpoint, String classHint, String methodHint) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (IOException exception) {
            return failure("runtime_mappings_invalid_payload", "runtime_mapping_parse",
                    "verify_runtime_mappings_payload", endpointEvidence(endpoint));
        }
        Set<String> routes = new LinkedHashSet<>();
        collect(payload, classHint, methodHint, routes);
        if (routes.isEmpty()) {
            return failure("runtime_mapping_not_found", "runtime_mapping_match",
                    "refine_runtime_mapping_hints", List.of("runtimeRouteCount=0"));
        }
        if (routes.size() > 1) {
            return failure("runtime_mapping_ambiguous", "runtime_mapping_match",
                    "disambiguate_runtime_mapping", List.of("runtimeRouteMatches=" + routes.size()));
        }
        String[] selected = routes.iterator().next().split(" ", 2);
        if (selected.length != 2 || !supportedMethod(selected[0])) {
            return failure("runtime_mappings_invalid_payload", "runtime_mapping_match",
                    "verify_runtime_mappings_payload", List.of("runtimeRoute=invalid"));
        }
        RouteSynthesisRecipeCandidate candidate = new RouteSynthesisRecipeCandidate(
                selected[0], selected[1], "", selected[1], null, List.of(), List.of(),
                List.of(ATTEMPT));
        return RouteSynthesisRuntimeMappingResolution.success(candidate,
                List.of("mapping_source=runtime_actuator", endpointEvidence(endpoint).get(0),
                        endpointEvidence(endpoint).get(1),
                        "runtime_handler=" + classHint + "#" + methodHint), List.of(ATTEMPT));
    }

    void collect(JsonNode node, String classHint, String methodHint, Set<String> routes) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> collect(value, classHint, methodHint, routes));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        HandlerRef handler = handler(node);
        if (classHint.equals(handler.className()) && methodHint.equals(handler.methodName())) {
            JsonNode conditions = node.path("details").path("requestMappingConditions");
            for (String method : values(conditions.path("methods"), true)) {
                for (String path : values(conditions.path("patterns"), false)) {
                    routes.add(method + " " + path);
                }
            }
            routesForPredicate(node.path("predicate"), routes);
        }
        node.elements().forEachRemaining(value -> collect(value, classHint, methodHint, routes));
    }

    boolean routesForPredicate(JsonNode predicate, Set<String> routes) {
        if (!predicate.isTextual()) {
            return false;
        }
        Matcher matcher = Pattern.compile("\\{([^\\[]+)\\[([^\\]]+)").matcher(predicate.asText());
        if (!matcher.find()) {
            return false;
        }
        List<String> methods = List.of(matcher.group(1).split(","));
        List<String> paths = List.of(matcher.group(2).split(","));
        for (String method : methods) {
            for (String path : paths) {
                String normalizedMethod = method.trim().toUpperCase();
                String normalizedPath = path.trim();
                if (!normalizedMethod.isEmpty() && normalizedPath.startsWith("/")) {
                    routes.add(normalizedMethod + " " + normalizedPath);
                }
            }
        }
        return true;
    }

    List<String> values(JsonNode values, boolean methods) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> {
                String text = value.isTextual() ? value.asText() : value.path("name").asText();
                if (!text.isBlank()) {
                    result.add(methods ? text.trim().toUpperCase() : normalizePath(text));
                }
            });
        }
        return result;
    }

    HandlerRef handler(JsonNode node) {
        JsonNode method = node.path("details").path("handlerMethod");
        if (method.path("className").isTextual() && method.path("name").isTextual()) {
            return new HandlerRef(method.path("className").asText(), method.path("name").asText());
        }
        Matcher matcher = HANDLER_PATTERN.matcher(node.path("handler").asText());
        return matcher.find() ? new HandlerRef(matcher.group(1), matcher.group(2)) : new HandlerRef("", "");
    }

    private String readBounded(InputStream body) throws IOException, ResponseTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new ResponseTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    List<String> endpointEvidence(URI endpoint) {
        return List.of("mappingsHost=" + endpoint.getHost(), "mappingsPath=" + endpoint.getPath());
    }

    String normalizePath(String path) {
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    boolean supportedMethod(String method) {
        return List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    RouteSynthesisRuntimeMappingResolution failure(
            String reason, String step, String nextAction, List<String> evidence) {
        return RouteSynthesisRuntimeMappingResolution.failure(reason, step, nextAction, evidence, List.of(ATTEMPT));
    }

    record HandlerRef(String className, String methodName) {
    }

    private static class ResponseTooLargeException extends Exception {
    }
}
