package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSize;

/** Joins XML documentation to explicit Java registrations and fails closed. */
public class OperationManifestAssembler {

    private OperationManifestAssembler() {
    }

    /**
     * Assembles the executable/documentation join in migration compatibility mode.
     * Strict kernel fixtures should use {@link #assembleStrict(List, OperationManifestDocument)}.
     */
    public static OperationManifest assemble(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document) {
        return assemble(registrations, document, true);
    }

    /** Assembles a manifest with strict cancellation-state validation enabled. */
    public static OperationManifest assembleStrict(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document) {
        return assemble(registrations, document, false);
    }

    /** Assembles with an explicit migration mode for strict future cutover validation. */
    static OperationManifest assemble(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document,
            boolean migrationCompatibilityMode) {
        Objects.requireNonNull(registrations, "registrations must not be null");
        Objects.requireNonNull(document, "manifest document must not be null");
        Map<OperationId, OperationRegistration<?, ?>> joined = new TreeMap<>();
        for (OperationRegistration<?, ?> registration
                : registrations) {
            if (registration == null) {
                throw new IllegalArgumentException("registrations must not contain null");
            }
            OperationId id = registration.descriptor().operationId();
            if (joined.containsKey(id)) {
                throw new IllegalArgumentException("duplicate executable operation: " + id.value());
            }
            joined.put(id, enrich(registration, document.documentation(id), migrationCompatibilityMode));
        }
        for (OperationId documentedId : document.operations().keySet()) {
            if (!joined.containsKey(documentedId)) {
                throw new IllegalArgumentException("orphan operation documentation: " + documentedId.value());
            }
        }
        return new OperationManifest(document.version(), new ArrayList<>(joined.values()));
    }

    static OperationRegistration<?, ?> enrich(
            OperationRegistration<?, ?> registration, OperationDocumentation documentation) {
        return enrich(registration, documentation, true);
    }

    static OperationRegistration<?, ?> enrich(
            OperationRegistration<?, ?> registration,
            OperationDocumentation documentation,
            boolean migrationCompatibilityMode) {
        OperationId id = registration.descriptor().operationId();
        if (documentation == null) {
            throw new IllegalArgumentException("missing operation documentation: " + id.value());
        }
        OperationDescriptor descriptor = registration.descriptor();
        OperationSchema inputSchema = registration.inputSchema();
        OperationSchema resultSchema = registration.resultSchema();
        if (!migrationCompatibilityMode) {
            validateBoundedBinding(registration, id);
        }
        OperationCancellationSupport.validate(
                registration.executor(), registration.safety(), migrationCompatibilityMode);
        OperationDocumentation effectiveDocumentation = documentation.withExecutableMetadata(
                executableArguments(id, documentation.arguments(), inputSchema), registration.safety());
        for (JsonNode example : effectiveDocumentation.examples()) {
            if (OperationJsonSize.measure(example, registration.safety().maxInputBytes()) < 0) {
                throw new IllegalArgumentException("manifest example exceeds input limit: " + id.value());
            }
            List<String> violations = OperationSchemaValidator.violations(inputSchema, example);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("manifest example is invalid for " + id.value()
                        + ": " + violations.getFirst());
            }
        }
        OperationDescriptorMetadata metadata = new OperationDescriptorMetadata(
                id,
                effectiveDocumentation.classification(),
                effectiveDocumentation,
                inputSchema,
                resultSchema,
                registration.safety());
        return registration.withDescriptor(descriptor.withMetadata(metadata));
    }

    static void validateBoundedBinding(
            OperationRegistration<?, ?> registration, OperationId id) {
        if (!(registration.decoder() instanceof BoundedOperationRequestDecoder<?>)) {
            throw new IllegalArgumentException(
                    "operation argument decoder must support bounded round trips: " + id.value());
        }
        if (!(registration.encoder() instanceof BoundedOperationResultEncoder<?>)) {
            throw new IllegalArgumentException(
                    "operation result encoder must support bounded streaming: " + id.value());
        }
    }

    public static List<OperationArgumentDocumentation> executableArguments(
            OperationId id,
            List<OperationArgumentDocumentation> arguments,
            OperationSchema schema) {
        JsonNode definition = schema.definition();
        JsonNode properties = definition.get("properties");
        List<OperationArgumentDocumentation> executable = new ArrayList<>();
        for (OperationArgumentDocumentation argument : arguments) {
            JsonNode property = properties == null ? null : properties.get(argument.name());
            if (property == null) {
                throw new IllegalArgumentException("manifest argument is not owned by Java type: "
                        + id.value() + "." + argument.name());
            }
            boolean schemaRequired = false;
            for (JsonNode required : definition.path("required")) {
                if (argument.name().equals(required.asText())) {
                    schemaRequired = true;
                    break;
                }
            }
            executable.add(argument.withSchema(
                    schemaType(property, definition), schemaRequired, property.get("default")));
        }
        if (properties != null) {
            for (var names = properties.fieldNames(); names.hasNext();) {
                String propertyName = names.next();
                if (arguments.stream().noneMatch(argument -> propertyName.equals(argument.name()))) {
                    throw new IllegalArgumentException("manifest argument documentation is missing: "
                            + id.value() + "." + propertyName);
                }
            }
        }
        return List.copyOf(executable);
    }

    static String schemaType(JsonNode schema, JsonNode root) {
        JsonNode reference = schema.get("$ref");
        if (reference != null) {
            JsonNode target = root.path("$defs").get(reference.asText().substring("#/$defs/".length()));
            if (target == null) {
                throw new IllegalArgumentException("operation schema reference is unresolved: " + reference.asText());
            }
            return schemaType(target, root);
        }
        String type = schema.path("type").asText("");
        if (!type.isBlank()) {
            return type;
        }
        JsonNode alternatives = schema.get("oneOf");
        if (alternatives != null && alternatives.isArray()) {
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            for (JsonNode alternative : alternatives) {
                String alternativeType = schemaType(alternative, root);
                if (!"null".equals(alternativeType)) {
                    types.add(alternativeType);
                }
            }
            if (types.size() == 1) {
                return types.iterator().next();
            }
        }
        throw new IllegalArgumentException("operation schema type is unresolved");
    }
}
