package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.execution.SuiteRuntimeEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves Regression runtime context from supplied values and project-owned environment bindings. */
final class RegressionRuntimeContextResolver {

    private final ObjectMapper mapper;

    RegressionRuntimeContextResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> resolve(JsonNode input) {
        Map<String, Object> values = input.path("providedContext").isObject()
                ? new LinkedHashMap<>(mapper.convertValue(input.path("providedContext"), new TypeReference<>() {
                })) : new LinkedHashMap<>();
        JsonNode bindings = input.path("credentialBindings");
        if (!bindings.isObject()) {
            return values;
        }
        Map<String, String> environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
        bindings.fields().forEachRemaining(binding -> {
            String environmentKey = binding.getValue().asText();
            String resolved = environment.get(environmentKey);
            if (!values.containsKey(binding.getKey()) && resolved != null && !resolved.isBlank()) {
                values.put(binding.getKey(), resolved);
            }
        });
        return values;
    }
}
