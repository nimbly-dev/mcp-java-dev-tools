package com.nimbly.mcpjavadevtools.server.core.operation;


import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable human and machine-readable documentation for one operation. */
public record OperationDocumentation(
        String summary,
        String description,
        String classification,
        List<OperationArgumentDocumentation> arguments,
        List<JsonNode> examples,
        List<String> tags,
        List<OperationAlias> aliases,
        String since,
        boolean deprecated,
        String replacementOperationId,
        OperationSafetyPolicy safety) {

    /** Validates and deterministically copies documentation supplied by XML. */
    public OperationDocumentation {
        Objects.requireNonNull(summary, "operation summary must not be null");
        Objects.requireNonNull(description, "operation description must not be null");
        Objects.requireNonNull(classification, "operation classification must not be null");
        Objects.requireNonNull(arguments, "operation arguments must not be null");
        Objects.requireNonNull(examples, "operation examples must not be null");
        Objects.requireNonNull(tags, "operation tags must not be null");
        Objects.requireNonNull(aliases, "operation aliases must not be null");
        Objects.requireNonNull(since, "operation since must not be null");
        if (summary.isBlank() || description.isBlank() || classification.isBlank() || since.isBlank()
                || summary.length() > 512 || description.length() > 8192) {
            throw new IllegalArgumentException("operation documentation is outside the supported bounds");
        }
        Map<String, OperationArgumentDocumentation> uniqueArguments = new LinkedHashMap<>();
        for (OperationArgumentDocumentation argument : arguments) {
            if (argument == null || uniqueArguments.put(argument.name(), argument) != null) {
                throw new IllegalArgumentException("duplicate operation argument: "
                        + (argument == null ? "null" : argument.name()));
            }
        }
        arguments = uniqueArguments.values().stream()
                .sorted(Comparator.comparing(OperationArgumentDocumentation::name))
                .toList();
        List<JsonNode> copiedExamples = new ArrayList<>();
        for (JsonNode example : examples) {
            if (example == null) {
                throw new IllegalArgumentException("operation examples must not contain null");
            }
            copiedExamples.add(example.deepCopy());
        }
        examples = Collections.unmodifiableList(copiedExamples);
        List<String> normalizedTags = tags.stream().map(tag -> {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("operation tags must not be blank");
            }
            return tag.trim();
        }).toList();
        if (normalizedTags.size() != normalizedTags.stream().distinct().count()) {
            throw new IllegalArgumentException("duplicate operation tag");
        }
        tags = normalizedTags.stream().sorted().toList();
        List<OperationAlias> normalizedAliases = aliases.stream()
                .map(Objects::requireNonNull).toList();
        if (normalizedAliases.size() != normalizedAliases.stream().distinct().count()) {
            throw new IllegalArgumentException("duplicate operation alias");
        }
        aliases = normalizedAliases.stream().sorted().toList();
        if (deprecated && (replacementOperationId == null || replacementOperationId.isBlank())) {
            throw new IllegalArgumentException("deprecated operation must name a replacement");
        }
        if (!deprecated && replacementOperationId != null && !replacementOperationId.isBlank()) {
            throw new IllegalArgumentException("replacement requires a deprecated operation");
        }
        if (replacementOperationId != null) {
            OperationId.of(replacementOperationId);
        }
    }

    @Override
    public List<JsonNode> examples() {
        return examples.stream().map(example -> (JsonNode) example.deepCopy()).toList();
    }

    /** Returns documentation with the Java-owned safety policy applied. */
    public OperationDocumentation withSafety(OperationSafetyPolicy replacement) {
        return new OperationDocumentation(
                summary, description, classification, arguments, examples, tags, aliases,
                since, deprecated, replacementOperationId, replacement);
    }

    /** Creates minimal documentation for a pre-manifest descriptor. */
    public static OperationDocumentation legacy(OperationId operationId, String sideEffect) {
        return new OperationDocumentation(
                operationId.value(),
                "Legacy descriptor documentation for " + operationId.value(),
                operationId.api(),
                List.of(),
                List.of(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()),
                List.of(operationId.api()),
                List.of(),
                "legacy",
                false,
                null,
                OperationSafetyPolicy.legacy(sideEffect));
    }
}
