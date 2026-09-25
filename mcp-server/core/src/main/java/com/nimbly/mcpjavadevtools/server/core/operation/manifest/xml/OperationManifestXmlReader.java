package com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationAlias;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationArgumentDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Package-owned reader for legacy and capability-owned operation documents. */
public final class OperationManifestXmlReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OperationManifestXmlReader() {
    }

    /** Reads one legacy manifest or one capability-owned Tool/action document. */
    public static OperationManifestDocument read(Document document) {
        Element root = document.getDocumentElement();
        if (root != null && "tool".equals(root.getTagName())) {
            OperationManifestXmlStructureValidator.validateBounds(document, 0);
            OperationManifestXmlSchemaValidator.validate(document);
            return new OperationManifestDocument(1, readCapability(root));
        }
        if (root == null || !"operations".equals(root.getTagName())) {
            throw new IllegalArgumentException("operation manifest root must be operations");
        }
        OperationManifestXmlStructureValidator.validate(document);
        int version;
        try {
            version = Integer.parseInt(attribute(root, "manifestVersion", true));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("operation manifest version is invalid", exception);
        }
        Map<OperationId, OperationDocumentation> operations = new LinkedHashMap<>();
        for (Element operation : children(root, "operation")) {
            OperationId id = OperationId.of(attribute(operation, "id", true));
            if (operations.put(id, readOperation(operation, false)) != null) {
                throw new IllegalArgumentException("duplicate operation documentation: " + id.value());
            }
        }
        if (children(root, null).size() != operations.size()) {
            throw new IllegalArgumentException("operation manifest contains an unsupported root element");
        }
        return new OperationManifestDocument(version, operations);
    }

    static Map<OperationId, OperationDocumentation> readCapability(Element root) {
        String toolName = attribute(root, "name", true);
        if (!"2".equals(attribute(root, "formatVersion", true))) {
            throw new IllegalArgumentException("unsupported operation capability version");
        }
        Map<OperationId, OperationDocumentation> operations = new LinkedHashMap<>();
        for (Element action : children(root, "action")) {
            String actionName = attribute(action, "name", true);
            String subject = attribute(action, "subject", false);
            if ("artifact_management".equals(toolName) != (subject != null)) {
                throw new IllegalArgumentException("operation action subject identity is invalid");
            }
            OperationId id = OperationId.of(toolName + "."
                    + (subject == null ? "" : subject + ".") + actionName);
            if (operations.put(id, readOperation(action, true)) != null) {
                throw new IllegalArgumentException("duplicate operation documentation: " + id.value());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(operations));
    }

    static OperationDocumentation readOperation(Element element, boolean schemaValidated) {
        String summary = attribute(element, "summary", false);
        String description = attribute(element, "description", false);
        String classification = attribute(element, "classification", false);
        summary = summary == null ? text(element, "summary", true) : summary;
        description = description == null ? text(element, "description", true) : description;
        classification = classification == null ? text(element, "classification", true) : classification;
        Element tags = child(element, "tags", false);
        List<String> tagValues = new ArrayList<>();
        if (tags != null) {
            for (Element tag : children(tags, "tag")) {
                tagValues.add(text(tag, null, true));
            }
        }
        return new OperationDocumentation(summary, description, classification,
                readArguments(element, schemaValidated), readExamples(element), tagValues,
                readAliases(element, schemaValidated),
                readSafety(child(element, "safety", false), schemaValidated));
    }

    static List<OperationArgumentDocumentation> readArguments(Element operation, boolean schemaValidated) {
        Element wrapper = child(operation, "arguments", false);
        if (wrapper == null) {
            return List.of();
        }
        List<OperationArgumentDocumentation> arguments = new ArrayList<>();
        for (Element element : children(wrapper, "argument")) {
            Element defaultElement = child(element, "default", false);
            JsonNode defaultValue = defaultElement == null ? null : json(text(defaultElement, null, true));
            String required = attribute(element, "required", true);
            if (!("true".equals(required) || "false".equals(required)
                    || (schemaValidated && ("1".equals(required) || "0".equals(required))))) {
                throw new IllegalArgumentException("manifest argument boolean attribute is invalid");
            }
            arguments.add(new OperationArgumentDocumentation(
                    attribute(element, "name", true), attribute(element, "description", true),
                    attribute(element, "type", true), "true".equals(required) || "1".equals(required),
                    defaultValue));
        }
        return List.copyOf(arguments);
    }

    static List<JsonNode> readExamples(Element operation) {
        Element wrapper = child(operation, "examples", false);
        if (wrapper == null) {
            return List.of();
        }
        List<JsonNode> examples = new ArrayList<>();
        for (Element element : children(wrapper, "example")) {
            examples.add(json(text(element, null, true)));
        }
        return List.copyOf(examples);
    }

    static List<OperationAlias> readAliases(Element operation, boolean schemaValidated) {
        Element wrapper = child(operation, "aliases", false);
        if (wrapper == null) {
            return List.of();
        }
        List<OperationAlias> aliases = new ArrayList<>();
        for (Element element : children(wrapper, "alias")) {
            String action = attribute(element, "action", false);
            String actionless = attribute(element, "actionless", false);
            if (actionless != null && !("true".equals(actionless) || "false".equals(actionless)
                    || (schemaValidated && ("1".equals(actionless) || "0".equals(actionless))))) {
                throw new IllegalArgumentException("manifest boolean attribute is invalid");
            }
            aliases.add(new OperationAlias(attribute(element, "tool", true), action == null ? "" : action,
                    actionless != null && ("true".equals(actionless) || "1".equals(actionless))));
        }
        return List.copyOf(aliases);
    }

    static OperationSafetyPolicy readSafety(Element element, boolean schemaValidated) {
        if (element == null) {
            return null;
        }
        String confirmation = attribute(element, "confirmationRequired", true);
        String cancellable = attribute(element, "cancellable", true);
        if (!(("true".equals(confirmation) || "false".equals(confirmation)
                || (schemaValidated && ("1".equals(confirmation) || "0".equals(confirmation))))
                && ("true".equals(cancellable) || "false".equals(cancellable)
                || (schemaValidated && ("1".equals(cancellable) || "0".equals(cancellable)))))) {
            throw new IllegalArgumentException("manifest safety boolean attribute is invalid");
        }
        return new OperationSafetyPolicy(attribute(element, "sideEffect", true),
                "true".equals(confirmation) || "1".equals(confirmation),
                attribute(element, "credentialPolicy", true), attribute(element, "redactionPolicy", true),
                Long.parseLong(attribute(element, "timeoutMs", true)),
                "true".equals(cancellable) || "1".equals(cancellable),
                Integer.parseInt(attribute(element, "maxInputBytes", true)),
                Integer.parseInt(attribute(element, "maxOutputBytes", true)));
    }

    static List<Element> children(Element parent, String name) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && (name == null || name.equals(element.getTagName()))) {
                elements.add(element);
            }
        }
        return elements;
    }

    static Element child(Element parent, String name, boolean required) {
        List<Element> matches = children(parent, name);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("duplicate manifest element: " + name);
        }
        if (matches.isEmpty() && required) {
            throw new IllegalArgumentException("missing manifest element: " + name);
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static String text(Element parent, String childName, boolean required) {
        Element element = childName == null ? parent : child(parent, childName, required);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().trim();
        if (required && value.isBlank()) {
            throw new IllegalArgumentException("manifest text must not be blank: " + childName);
        }
        return value;
    }

    static String attribute(Element element, String name, boolean required) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("missing manifest attribute: " + name);
            }
            return null;
        }
        return value.trim();
    }

    static JsonNode json(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null) {
                throw new IllegalArgumentException("manifest JSON value is missing");
            }
            return parsed;
        } catch (IOException exception) {
            throw new IllegalArgumentException("manifest JSON value is invalid", exception);
        }
    }

}

/** Validates public capability DOM reads against the fixed shared syntax contract. */
final class OperationManifestXmlSchemaValidator {

    private static final int MAX_SCHEMA_BYTES = 262_144;

    private OperationManifestXmlSchemaValidator() {
    }

    static void validate(Document document) {
        try (InputStream source = OperationManifestXmlReader.class.getClassLoader().getResourceAsStream(
                "META-INF/mcpjvm/operations/operation-documents.xsd")) {
            if (source == null) {
                throw new IllegalArgumentException("operation capability schema is missing");
            }
            byte[] schemaBytes = source.readNBytes(MAX_SCHEMA_BYTES + 1);
            if (schemaBytes.length > MAX_SCHEMA_BYTES) {
                throw new IllegalArgumentException("operation capability schema exceeds its byte bound");
            }
            schema(schemaBytes)
                    .newValidator().validate(new DOMSource(document));
        } catch (IOException | SAXException exception) {
            throw new IllegalArgumentException("operation capability XML is invalid", exception);
        }
    }

    static Schema schema(byte[] source) throws SAXException {
        return OperationManifestXmlSecurity.schema(source);
    }
}
