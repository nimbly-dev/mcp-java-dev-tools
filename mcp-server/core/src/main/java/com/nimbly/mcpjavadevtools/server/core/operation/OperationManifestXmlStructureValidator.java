package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Validates the bounded XML element and attribute structure before model reading. */
final class OperationManifestXmlStructureValidator {

    private static final int MAX_ELEMENTS = 4096;
    private static final Set<String> OPERATION_ATTRIBUTES = Set.of(
            "id", "summary", "description", "classification", "since", "replacement", "deprecated");

    private OperationManifestXmlStructureValidator() {
    }

    static void validate(Document document) {
        if (document.getElementsByTagName("*").getLength() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("operation manifest contains too many elements");
        }
        Element root = document.getDocumentElement();
        for (Element operation : OperationManifestXmlReader.children(root, "operation")) {
            attributes(operation, OPERATION_ATTRIBUTES);
            for (Element child : OperationManifestXmlReader.children(operation, null)) {
                switch (child.getTagName()) {
                    case "summary", "description", "classification", "arguments", "examples", "tags", "aliases" ->
                            attributes(child, Set.of());
                    case "safety" -> attributes(child, Set.of(
                            "sideEffect", "confirmationRequired", "credentialPolicy", "redactionPolicy",
                            "timeoutMs", "cancellable", "maxInputBytes", "maxOutputBytes"));
                    case "compatibility" -> attributes(child, Set.of("since", "replacement", "deprecated"));
                    default -> throw new IllegalArgumentException(
                            "operation documentation contains an unsupported element");
                }
                nested(child);
            }
        }
    }

    static void nested(Element element) {
        switch (element.getTagName()) {
            case "arguments" -> {
                for (Element argument : OperationManifestXmlReader.children(element, "argument")) {
                    attributes(argument, Set.of("name", "description", "type", "required"));
                    Element defaultElement = OperationManifestXmlReader.child(argument, "default", false);
                    if (defaultElement != null) {
                        attributes(defaultElement, Set.of());
                    }
                    if (OperationManifestXmlReader.children(argument, null).size()
                            > (defaultElement == null ? 0 : 1)) {
                        throw new IllegalArgumentException("operation argument contains an unsupported element");
                    }
                }
            }
            case "examples" -> {
                for (Element example : OperationManifestXmlReader.children(element, "example")) {
                    attributes(example, Set.of());
                }
            }
            case "tags" -> {
                for (Element tag : OperationManifestXmlReader.children(element, "tag")) {
                    attributes(tag, Set.of());
                }
            }
            case "aliases" -> {
                for (Element alias : OperationManifestXmlReader.children(element, "alias")) {
                    attributes(alias, Set.of("tool", "action", "actionless"));
                }
            }
            case "safety", "compatibility", "summary", "description", "classification" -> {
                if (!OperationManifestXmlReader.children(element, null).isEmpty()) {
                    throw new IllegalArgumentException("operation documentation contains unsupported nesting");
                }
            }
            default -> throw new IllegalArgumentException("operation manifest contains an unsupported element");
        }
    }

    static void attributes(Element element, Set<String> allowed) {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            String name = element.getAttributes().item(index).getNodeName();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("operation manifest contains an unsupported attribute");
            }
        }
    }
}
