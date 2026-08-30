package com.nimbly.mcpjavadevtools.server.core.operation;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Package-owned DOM-to-model reader for the operation manifest format. */
public final class OperationManifestXmlReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private OperationManifestXmlReader() {
    }

    public static OperationManifestDocument read(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !"operations".equals(root.getTagName())) {
            throw new IllegalArgumentException("operation manifest root must be operations");
        }
        OperationManifestXmlStructureValidator.validate(document);
        for (int index = 0; index < root.getAttributes().getLength(); index++) {
            if (!"manifestVersion".equals(root.getAttributes().item(index).getNodeName())) {
                throw new IllegalArgumentException("operation manifest contains an unsupported root attribute");
            }
        }
        int version;
        try {
            version = Integer.parseInt(attribute(root, "manifestVersion", true));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("operation manifest version is invalid", exception);
        }
        Map<OperationId, OperationDocumentation> operations = new LinkedHashMap<>();
        for (Element operation : children(root, "operation")) {
            for (int index = 0; index < operation.getAttributes().getLength(); index++) {
                if (!Set.of("id", "summary", "description", "classification", "since",
                        "replacement", "deprecated").contains(
                                operation.getAttributes().item(index).getNodeName())) {
                    throw new IllegalArgumentException("operation documentation contains an unsupported attribute");
                }
            }
            OperationId id = OperationId.of(attribute(operation, "id", true));
            if (operations.put(id, readOperation(operation)) != null) {
                throw new IllegalArgumentException("duplicate operation documentation: " + id.value());
            }
        }
        if (children(root, null).size() != children(root, "operation").size()) {
            throw new IllegalArgumentException("operation manifest contains an unsupported root element");
        }
        return new OperationManifestDocument(version, operations);
    }

    static OperationDocumentation readOperation(Element element) {
        for (Element child : children(element, null)) {
            if (!Set.of("summary", "description", "arguments", "examples", "tags", "aliases",
                    "safety", "compatibility").contains(child.getTagName())) {
                throw new IllegalArgumentException("operation documentation contains an unsupported element");
            }
        }
        String summary = attribute(element, "summary", false);
        String description = attribute(element, "description", false);
        String classification = attribute(element, "classification", false);
        summary = summary == null ? text(element, "summary", true) : summary;
        description = description == null ? text(element, "description", true) : description;
        classification = classification == null ? text(element, "classification", true) : classification;
        Element compatibility = child(element, "compatibility", false);
        String directSince = attribute(element, "since", false);
        String directReplacement = attribute(element, "replacement", false);
        String directDeprecated = attribute(element, "deprecated", false);
        String since = directSince == null && compatibility != null
                ? attribute(compatibility, "since", false) : directSince;
        String replacement = directReplacement == null && compatibility != null
                ? attribute(compatibility, "replacement", false) : directReplacement;
        String deprecatedValue = directDeprecated == null && compatibility != null
                ? attribute(compatibility, "deprecated", false) : directDeprecated;
        boolean deprecated = false;
        if (deprecatedValue != null) {
            if (!"true".equals(deprecatedValue) && !"false".equals(deprecatedValue)) {
                throw new IllegalArgumentException("manifest deprecated attribute is invalid");
            }
            deprecated = Boolean.parseBoolean(deprecatedValue);
        }
        Element safety = child(element, "safety", false);
        return new OperationDocumentation(
                summary,
                description,
                classification,
                readArguments(element),
                readExamples(element),
                readTags(element),
                readAliases(element),
                since == null ? "1" : since,
                deprecated,
                replacement,
                readSafety(safety));
    }

    static List<OperationArgumentDocumentation> readArguments(Element operation) {
        Element wrapper = child(operation, "arguments", false);
        if (wrapper == null) {
            return List.of();
        }
        List<OperationArgumentDocumentation> arguments = new ArrayList<>();
        for (Element element : children(wrapper, "argument")) {
            JsonNode defaultValue = null;
            Element defaultElement = child(element, "default", false);
            if (defaultElement != null) {
                defaultValue = json(text(defaultElement, null, true));
            }
            String required = attribute(element, "required", true);
            if (!"true".equals(required) && !"false".equals(required)) {
                throw new IllegalArgumentException("manifest argument boolean attribute is invalid");
            }
            arguments.add(new OperationArgumentDocumentation(
                    attribute(element, "name", true),
                    attribute(element, "description", true),
                    attribute(element, "type", true),
                    Boolean.parseBoolean(required),
                    defaultValue));
        }
        if (children(wrapper, null).size() != arguments.size()) {
            throw new IllegalArgumentException("operation arguments contain an unsupported element");
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
        if (children(wrapper, null).size() != examples.size()) {
            throw new IllegalArgumentException("operation examples are missing or unsupported");
        }
        return List.copyOf(examples);
    }

    static List<String> readTags(Element operation) {
        Element wrapper = child(operation, "tags", false);
        if (wrapper == null) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Element element : children(wrapper, "tag")) {
            tags.add(text(element, null, true));
        }
        if (children(wrapper, null).size() != tags.size()) {
            throw new IllegalArgumentException("operation tags contain an unsupported element");
        }
        return List.copyOf(tags);
    }

    static List<OperationAlias> readAliases(Element operation) {
        Element wrapper = child(operation, "aliases", false);
        if (wrapper == null) {
            return List.of();
        }
        List<OperationAlias> aliases = new ArrayList<>();
        for (Element element : children(wrapper, "alias")) {
            String action = attribute(element, "action", false);
            String actionlessValue = attribute(element, "actionless", false);
            if (actionlessValue != null && !"true".equals(actionlessValue)
                    && !"false".equals(actionlessValue)) {
                throw new IllegalArgumentException("manifest boolean attribute is invalid");
            }
            boolean actionless = actionlessValue != null && Boolean.parseBoolean(actionlessValue);
            aliases.add(new OperationAlias(
                    attribute(element, "tool", true), action == null ? "" : action, actionless));
        }
        if (children(wrapper, null).size() != aliases.size()) {
            throw new IllegalArgumentException("operation aliases contain an unsupported element");
        }
        return List.copyOf(aliases);
    }

    static OperationSafetyPolicy readSafety(Element element) {
        if (element == null) {
            return null;
        }
        String confirmation = attribute(element, "confirmationRequired", true);
        String cancellable = attribute(element, "cancellable", true);
        if (!("true".equals(confirmation) || "false".equals(confirmation))
                || !("true".equals(cancellable) || "false".equals(cancellable))) {
            throw new IllegalArgumentException("manifest safety boolean attribute is invalid");
        }
        return new OperationSafetyPolicy(
                attribute(element, "sideEffect", true),
                Boolean.parseBoolean(confirmation),
                attribute(element, "credentialPolicy", true),
                attribute(element, "redactionPolicy", true),
                Long.parseLong(attribute(element, "timeoutMs", true)),
                Boolean.parseBoolean(cancellable),
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
