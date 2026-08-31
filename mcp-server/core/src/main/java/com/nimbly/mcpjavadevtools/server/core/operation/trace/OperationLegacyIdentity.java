package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;

/** Exact released invocation identity retained beside one canonical operation. */
public record OperationLegacyIdentity(
        String toolName,
        String action,
        boolean actionless,
        Map<String, String> discriminators,
        String normalization,
        String resultComparison,
        String parityScenario) {

    /** Validates and defensively copies one released invocation identity. */
    public OperationLegacyIdentity {
        Objects.requireNonNull(toolName, "legacy tool name must not be null");
        Objects.requireNonNull(action, "legacy action must not be null");
        Objects.requireNonNull(discriminators, "legacy discriminators must not be null");
        Objects.requireNonNull(normalization, "legacy normalization must not be null");
        Objects.requireNonNull(resultComparison, "legacy result comparison must not be null");
        Objects.requireNonNull(parityScenario, "legacy parity scenario must not be null");
        toolName = toolName.trim();
        action = action.trim();
        normalization = normalization.trim();
        resultComparison = resultComparison.trim();
        parityScenario = parityScenario.trim();
        if (toolName.isBlank() || (!actionless && action.isBlank()) || (actionless && !action.isBlank())
                || toolName.length() > 128 || action.length() > 128
                || normalization.isBlank() || resultComparison.isBlank() || parityScenario.isBlank()
                || normalization.length() > 512 || resultComparison.length() > 512
                || parityScenario.length() > 512) {
            throw new IllegalArgumentException("legacy invocation identity is outside its bounds");
        }
        Map<String, String> copied = new TreeMap<>();
        for (Map.Entry<String, String> entry : discriminators.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getKey().isBlank() || entry.getValue().isBlank()
                    || entry.getKey().length() > 128 || entry.getValue().length() > 512) {
                throw new IllegalArgumentException("legacy discriminator is outside its bounds");
            }
            if (copied.put(entry.getKey().trim(), entry.getValue().trim()) != null) {
                throw new IllegalArgumentException("duplicate legacy discriminator");
            }
        }
        discriminators = Collections.unmodifiableMap(new LinkedHashMap<>(copied));
    }

    /** Creates explicit actionful compatibility metadata for the old descriptor API. */
    public static OperationLegacyIdentity fromDescriptor(OperationDescriptor descriptor) {
        return new OperationLegacyIdentity(
                descriptor.toolName(),
                descriptor.action(),
                false,
                Map.of(),
                "legacy_tool_action_to_canonical_operation_id",
                "deterministic_json_result",
                descriptor.operationId().value());
    }

    /** Returns the deterministic inventory projection for this released identity. */
    public Map<String, String> inventory(OperationDescriptor descriptor) {
        Map<String, String> values = new TreeMap<>();
        values.put("legacyTool", toolName);
        values.put("legacyAction", action);
        values.put("actionless", Boolean.toString(actionless));
        for (Map.Entry<String, String> discriminator : discriminators.entrySet()) {
            values.put("legacyDiscriminator." + discriminator.getKey(), discriminator.getValue());
        }
        values.put("operationId", descriptor.operationId().value());
        values.put("inputSchema", descriptor.inputSchema().definition().toString());
        values.put("normalization", normalization);
        values.put("resultComparison", resultComparison);
        values.put("parityScenario", parityScenario);
        return Collections.unmodifiableMap(values);
    }
}
