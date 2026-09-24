package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Compatibility or orchestration provenance retained beside one canonical operation. */
public record OperationProvenance(
        OperationProvenanceKind kind,
        String invocationTool,
        String invocationAction,
        boolean actionless,
        Map<String, String> discriminators,
        String normalization,
        String resultComparison,
        String parityScenario) {

    /** Validates and defensively copies one provenance declaration. */
    public OperationProvenance {
        kind = Objects.requireNonNull(kind, "provenance kind must not be null");
        invocationTool = bounded(invocationTool, "provenance tool");
        invocationAction = boundedAction(invocationAction, actionless);
        discriminators = copyDiscriminators(discriminators);
        normalization = boundedDetail(normalization, "normalization");
        resultComparison = boundedDetail(resultComparison, "result comparison");
        parityScenario = boundedDetail(parityScenario, "parity scenario");
        if (kind == OperationProvenanceKind.NEW_DIRECT_CDE_OPERATION && actionless) {
            throw new IllegalArgumentException("direct CDE provenance must name an orchestration action");
        }
    }

    /** Creates a mapping from one released invocation to its canonical operation. */
    public static OperationProvenance released(
            String tool,
            String action,
            Map<String, String> discriminators,
            String normalization,
            String resultComparison,
            String parityScenario) {
        return new OperationProvenance(
                OperationProvenanceKind.RELEASED_LEGACY_INVOCATION,
                tool, action, false, discriminators,
                normalization, resultComparison, parityScenario);
    }

    /** Creates a mapping from one released actionless invocation to its canonical operation. */
    public static OperationProvenance releasedActionless(
            String tool,
            Map<String, String> discriminators,
            String normalization,
            String resultComparison,
            String parityScenario) {
        return new OperationProvenance(
                OperationProvenanceKind.RELEASED_LEGACY_INVOCATION,
                tool, "", true, discriminators,
                normalization, resultComparison, parityScenario);
    }

    /** Creates an intentionally new direct operation with released orchestration provenance. */
    public static OperationProvenance direct(
            String provenanceTool,
            String provenanceAction,
            Map<String, String> discriminators,
            String normalization,
            String resultComparison,
            String parityScenario) {
        return new OperationProvenance(
                OperationProvenanceKind.NEW_DIRECT_CDE_OPERATION,
                provenanceTool, provenanceAction, false, discriminators,
                normalization, resultComparison, parityScenario);
    }

    /** Creates released compatibility metadata for the pre-registration descriptor API. */
    public static OperationProvenance fromDescriptor(OperationDescriptor descriptor) {
        return released(
                descriptor.toolName(), descriptor.action(), Map.of(),
                "released_tool_action_to_canonical_operation_id",
                "deterministic_json_result",
                descriptor.operationId().value());
    }

    /** Returns the deterministic inventory projection for this provenance declaration. */
    public Map<String, String> inventory(OperationDescriptor descriptor) {
        Map<String, String> values = new TreeMap<>();
        values.put("provenanceKind", kind.name());
        String prefix = kind == OperationProvenanceKind.RELEASED_LEGACY_INVOCATION
                ? "released" : "provenance";
        values.put(prefix + "Tool", invocationTool);
        values.put(prefix + "Action", invocationAction);
        if (kind == OperationProvenanceKind.RELEASED_LEGACY_INVOCATION) {
            values.put("actionless", Boolean.toString(actionless));
        }
        for (Map.Entry<String, String> discriminator : discriminators.entrySet()) {
            values.put(prefix + "Discriminator." + discriminator.getKey(), discriminator.getValue());
        }
        values.put("operationId", descriptor.operationId().value());
        values.put("inputSchema", descriptor.inputSchema().definition().toString());
        values.put("normalization", normalization);
        values.put("resultComparison", resultComparison);
        values.put("parityScenario", parityScenario);
        return Collections.unmodifiableMap(values);
    }

    static String bounded(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new IllegalArgumentException(name + " is outside its bounds");
        }
        return normalized;
    }

    static String boundedAction(String value, boolean actionless) {
        Objects.requireNonNull(value, "provenance action must not be null");
        String normalized = value.trim();
        if ((actionless && !normalized.isBlank()) || (!actionless && normalized.isBlank())
                || normalized.length() > 128) {
            throw new IllegalArgumentException("provenance action is outside its bounds");
        }
        return normalized;
    }

    static String boundedDetail(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > 512) {
            throw new IllegalArgumentException(name + " is outside its bounds");
        }
        return normalized;
    }

    static Map<String, String> copyDiscriminators(Map<String, String> values) {
        Objects.requireNonNull(values, "provenance discriminators must not be null");
        Map<String, String> copied = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = bounded(entry.getKey(), "provenance discriminator name");
            String value = boundedDetail(entry.getValue(), "provenance discriminator value");
            if (copied.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate provenance discriminator");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(copied));
    }
}
