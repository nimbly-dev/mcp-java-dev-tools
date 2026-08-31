package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;

/** Joins XML documentation to explicit Java registrations and fails closed. */
public final class OperationManifestAssembler {

    private OperationManifestAssembler() {
    }

    /** Assembles the exact executable/documentation join for one manifest version. */
    public static OperationManifest assemble(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document) {
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
            joined.put(id, enrich(registration, document.documentation(id)));
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
        OperationId id = registration.descriptor().operationId();
        if (documentation == null) {
            throw new IllegalArgumentException("missing operation documentation: " + id.value());
        }
        OperationDescriptor descriptor = registration.descriptor();
        if (documentation.safety() != null
                && !registration.safety().sideEffect().equals(documentation.safety().sideEffect())) {
            throw new IllegalArgumentException("manifest safety side effect mismatch: " + id.value());
        }
        validateAliases(descriptor, documentation.aliases(), registration.legacyIdentity());
        OperationSchema inputSchema = registration.inputSchema();
        OperationSchema resultSchema = registration.resultSchema();
        OperationDocumentation effectiveDocumentation = documentation.withSafety(registration.safety());
        validateArguments(id, documentation.arguments(), inputSchema);
        for (JsonNode example : effectiveDocumentation.examples()) {
            if (example.toString().getBytes(StandardCharsets.UTF_8).length
                    > registration.safety().maxInputBytes()) {
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

    public static void validateArguments(
            OperationId id,
            List<OperationArgumentDocumentation> arguments,
            OperationSchema schema) {
        JsonNode definition = schema.definition();
        JsonNode properties = definition.get("properties");
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
            if (argument.required() != schemaRequired) {
                throw new IllegalArgumentException("manifest argument requiredness mismatch: "
                        + id.value() + "." + argument.name());
            }
            if (!typeMatches(argument.type(), property, definition)) {
                throw new IllegalArgumentException("manifest argument type mismatch: "
                        + id.value() + "." + argument.name());
            }
            if (argument.defaultValue() != null) {
                List<String> violations = OperationSchemaValidator.violations(
                        property, definition, argument.defaultValue());
                if (!violations.isEmpty()) {
                    throw new IllegalArgumentException("manifest argument default is invalid: "
                            + id.value() + "." + argument.name());
                }
            }
            validateDefault(id, argument, property);
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
    }

    static void validateDefault(
            OperationId id, OperationArgumentDocumentation argument, JsonNode property) {
        JsonNode schemaDefault = property.get("default");
        JsonNode documentedDefault = argument.defaultValue();
        if (schemaDefault == null && documentedDefault != null) {
            throw new IllegalArgumentException("manifest argument default is not Java-owned: "
                    + id.value() + "." + argument.name());
        }
        if (schemaDefault != null && (documentedDefault == null
                || !schemaDefault.equals(documentedDefault))) {
            throw new IllegalArgumentException("manifest argument default mismatch: "
                    + id.value() + "." + argument.name());
        }
    }

    static boolean typeMatches(String documented, JsonNode schema, JsonNode root) {
        String normalized = documented.toLowerCase(java.util.Locale.ROOT);
        JsonNode reference = schema.get("$ref");
        if (reference != null) {
            JsonNode target = root.path("$defs").get(reference.asText().substring("#/$defs/".length()));
            return target != null && typeMatches(normalized, target, root);
        }
        if (typeMatches(normalized, schema.path("type").asText(""))) {
            return true;
        }
        JsonNode alternatives = schema.get("oneOf");
        if (alternatives != null && alternatives.isArray()) {
            for (JsonNode alternative : alternatives) {
                if (typeMatches(normalized, alternative, root)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean typeMatches(String documented, String generated) {
        String normalized = documented.toLowerCase(java.util.Locale.ROOT);
        return generated.equals(normalized)
                || ("int".equals(normalized) && "integer".equals(generated))
                || ("long".equals(normalized) && "integer".equals(generated))
                || ("float".equals(normalized) && "number".equals(generated))
                || ("double".equals(normalized) && "number".equals(generated));
    }

    static void validateAliases(
            OperationDescriptor descriptor,
            List<OperationAlias> aliases,
            OperationLegacyIdentity legacyIdentity) {
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("released inventory alias is missing: "
                    + descriptor.operationId().value());
        }
        for (OperationAlias alias : aliases) {
            if (!legacyIdentity.toolName().equals(alias.toolName())
                    || !legacyIdentity.action().equals(alias.action())
                    || legacyIdentity.actionless() != alias.actionless()) {
                throw new IllegalArgumentException("released inventory alias mismatch: "
                        + descriptor.operationId().value());
            }
        }
    }
}
