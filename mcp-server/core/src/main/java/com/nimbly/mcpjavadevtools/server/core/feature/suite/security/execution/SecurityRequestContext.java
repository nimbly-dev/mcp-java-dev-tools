package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.execution.SuiteRuntimeEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves per-invocation credentials, fixture values and authentication headers. */
class SecurityRequestContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern CONTEXT_EXPRESSION = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, String> values;
    private final String reasonCode;
    private final SecurityRequestBudget budget;
    private final JsonNode boundary;

    private SecurityRequestContext(Map<String, String> values, String reasonCode, JsonNode contract) {
        this.values = Map.copyOf(values);
        this.reasonCode = reasonCode;
        this.budget = new SecurityRequestBudget(contract);
        this.boundary = contract.path("targetBoundary");
    }

    String reasonCode() {
        return reasonCode;
    }

    static SecurityRequestContext from(JsonNode input, JsonNode contract) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode bindings = input.path("credentialBindings");
        Map<String, String> environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
        SecurityCredentialRefresh.Result refresh = new SecurityCredentialRefresh()
                .refresh(input.path("credentialSource"), environment);
        if (!refresh.successful()) {
            return new SecurityRequestContext(Map.of(), refresh.reasonCode(), contract);
        }
        environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
        if (bindings.isObject()) {
            var bindingEntries = bindings.fields();
            while (bindingEntries.hasNext()) {
                var binding = bindingEntries.next();
                String value = environment.get(binding.getValue().asText());
                if (value != null && !value.isBlank()) {
                    values.put(binding.getKey(), value);
                }
            }
        }
        for (JsonNode profile : contract.path("authenticationProfiles")) {
            if ("anonymous".equals(profile.path("kind").asText())) {
                continue;
            }
            String ref = profile.path("credentialRef").asText();
            if (ref.isBlank() || !values.containsKey(ref)) {
                return new SecurityRequestContext(Map.of(), "security_runtime_credentials_unavailable", contract);
            }
        }
        return new SecurityRequestContext(values, null, contract);
    }

    Map<String, String> fixtureContext(JsonNode entrypoint) {
        Map<String, String> context = new LinkedHashMap<>();
        collectFixtures(context, boundary.path("fixtureContext"));
        collectFixtures(context, boundary.path("details").path("fixtureContext"));
        collectFixtures(context, entrypoint.path("details").path("fixtureContext"));
        return context;
    }

    private static void collectFixtures(Map<String, String> context, JsonNode fixtures) {
        fixtures.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                context.put(entry.getKey(), value.asText());
            }
        });
    }

    JsonNode resolveRequest(JsonNode request, Map<String, String> fixtures) {
        JsonNode value = request == null ? MAPPER.createObjectNode() : request;
        if (value.path("transport").isObject() && "http".equals(value.path("transport").path("type").asText())) {
            value = value.path("transport");
        }
        ObjectNode resolved = value.isObject() ? ((ObjectNode) value).deepCopy() : MAPPER.createObjectNode();
        for (String field : List.of("pathParameters", "query", "queryParameters", "body")) {
            if (resolved.has(field)) {
                resolved.set(field, resolveValues(resolved.path(field), fixtures));
            }
        }
        Map<String, String> headerContext = new LinkedHashMap<>(fixtures);
        headerContext.putAll(values);
        ObjectNode headers = resolved.putObject("headers");
        requestHeaders(value.path("headers")).forEach((name, candidate) -> {
            String credential = resolve(candidate);
            headers.put(name, credential.equals(candidate) ? resolveString(candidate, headerContext) : credential);
        });
        return resolved;
    }

    private String resolve(String candidate) {
        for (Map.Entry<String, String> credential : values.entrySet()) {
            String placeholder = "${" + credential.getKey() + "}";
            if (candidate.equals(placeholder)) {
                return credential.getValue();
            }
            if (candidate.equals("Bearer " + placeholder)) {
                return bearer(credential.getValue());
            }
            if (candidate.equals("Basic " + placeholder)) {
                return prefixed(credential.getValue(), "Basic");
            }
        }
        return candidate;
    }

    void apply(JsonNode profile, Map<String, String> headers) {
        String kind = profile.path("kind").asText();
        if ("anonymous".equals(kind)) {
            return;
        }
        String header = "api_key".equals(kind) ? "X-API-Key" : "Authorization";
        if (headers.keySet().stream().anyMatch(name -> header.equalsIgnoreCase(name))) {
            return;
        }
        String credential = values.get(profile.path("credentialRef").asText());
        if ("bearer".equals(kind)) {
            headers.put("Authorization", bearer(credential));
        } else if ("basic".equals(kind)) {
            headers.put("Authorization", prefixed(credential, "Basic"));
        } else if ("api_key".equals(kind)) {
            headers.put("X-API-Key", credential);
        } else {
            headers.put("Authorization", credential);
        }
    }

    private static String bearer(String credential) {
        return prefixed(credential, "Bearer");
    }

    private static String prefixed(String credential, String scheme) {
        String prefix = scheme + " ";
        return credential.regionMatches(true, 0, prefix, 0, prefix.length()) ? credential : prefix + credential;
    }

    static String resolveString(String value, Map<String, String> context) {
        var expressions = CONTEXT_EXPRESSION.matcher(value);
        return expressions.replaceAll(match -> {
            String key = match.group(1);
            String fixtureKey = key.startsWith("fixture.") ? key.substring("fixture.".length()) : key;
            String resolved = context.getOrDefault(key, context.get(fixtureKey));
            if (resolved == null) {
                throw new IllegalArgumentException("unresolved Security fixture expression");
            }
            return java.util.regex.Matcher.quoteReplacement(resolved);
        });
    }

    private static JsonNode resolveValues(JsonNode value, Map<String, String> context) {
        if (value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(resolveString(value.asText(), context));
        }
        if (value.isObject()) {
            ObjectNode resolved = MAPPER.createObjectNode();
            value.fields().forEachRemaining(entry -> resolved.set(entry.getKey(), resolveValues(entry.getValue(), context)));
            return resolved;
        }
        if (value.isArray()) {
            var resolved = MAPPER.createArrayNode();
            value.forEach(element -> resolved.add(resolveValues(element, context)));
            return resolved;
        }
        return value;
    }

    static Map<String, String> requestHeaders(JsonNode headers) {
        Map<String, String> values = new LinkedHashMap<>();
        if (headers.isObject()) {
            headers.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        }
        return values;
    }

    SecurityRequestBudget budget() {
        return budget;
    }

    JsonNode boundary() {
        return boundary;
    }
}
