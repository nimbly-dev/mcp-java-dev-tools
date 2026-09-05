package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import com.fasterxml.jackson.databind.JsonNode;

/** Applies the manifest redaction policy to JSON results after the preflight size check. */
public class OperationValueRedactor {

    private OperationValueRedactor() {
    }

    public static JsonNode redact(JsonNode value, String policy) {
        return BoundedOperationValueRedactor.redact(value, policy, () -> false);
    }

    static boolean sensitive(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("authorization") || normalized.contains("private_key")
                || normalized.equals("apikey") || normalized.equals("api_key");
    }
}
