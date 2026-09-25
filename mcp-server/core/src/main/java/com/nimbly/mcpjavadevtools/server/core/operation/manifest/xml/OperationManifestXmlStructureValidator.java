package com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml;

import static com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader.child;
import static com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader.children;

import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates the legacy DOM envelope; capability syntax is owned by the shared XSD. */
final class OperationManifestXmlStructureValidator {

    private static final int MAX_ELEMENTS = 4096;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT_BYTES = 65_536;
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_ALIASES = 16;
    private static final int MAX_EXAMPLES = 16;
    private static final Set<String> LEGACY_OPERATION_ATTRIBUTES = Set.of(
            "id", "summary", "description", "classification");

    private OperationManifestXmlStructureValidator() {
    }

    static void validate(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !"operations".equals(root.getTagName())) {
            throw new IllegalArgumentException("operation manifest root must be operations");
        }
        validateBounds(document, 0);
        attributes(root, Set.of("manifestVersion"));
        for (Element operation : children(root, "operation")) {
            validateDocumentationElement(operation);
        }
        if (children(root, null).size() != children(root, "operation").size()) {
            throw new IllegalArgumentException("operation manifest contains an unsupported root element");
        }
    }

    static void validateDocumentationElement(Element operation) {
        attributes(operation, LEGACY_OPERATION_ATTRIBUTES);
        for (Element child : children(operation, null)) {
            switch (child.getTagName()) {
                case "summary", "description", "classification", "arguments", "examples", "tags", "aliases" ->
                        attributes(child, Set.of());
                case "safety" -> attributes(child, Set.of(
                        "sideEffect", "confirmationRequired", "credentialPolicy", "redactionPolicy",
                        "timeoutMs", "cancellable", "maxInputBytes", "maxOutputBytes"));
                default -> throw new IllegalArgumentException(
                        "operation documentation contains an unsupported element");
            }
            validateNested(child);
        }
    }

    static void validateNested(Element element) {
        switch (element.getTagName()) {
            case "arguments" -> validateArguments(element);
            case "examples" -> validateExamples(element);
            case "tags" -> validateTags(element);
            case "aliases" -> validateAliases(element);
            case "safety", "summary", "description", "classification" -> {
                if (!children(element, null).isEmpty()) {
                    throw new IllegalArgumentException("operation documentation contains unsupported nesting");
                }
            }
            default -> throw new IllegalArgumentException("operation manifest contains an unsupported element");
        }
    }

    static void validateArguments(Element element) {
        var arguments = children(element, "argument");
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("operation arguments exceed their count bound");
        }
        for (Element argument : arguments) {
            attributes(argument, Set.of("name", "description", "type", "required"));
            Element defaultElement = child(argument, "default", false);
            if (defaultElement != null) {
                attributes(defaultElement, Set.of());
            }
            if (children(argument, null).size() > (defaultElement == null ? 0 : 1)) {
                throw new IllegalArgumentException("operation argument contains an unsupported element");
            }
        }
        if (children(element, null).size() != arguments.size()) {
            throw new IllegalArgumentException("operation arguments contain an unsupported element");
        }
    }

    static void validateExamples(Element element) {
        var examples = children(element, "example");
        if (examples.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("operation examples exceed their count bound");
        }
        for (Element example : examples) {
            attributes(example, Set.of());
        }
        if (children(element, null).size() != examples.size()) {
            throw new IllegalArgumentException("operation examples contain an unsupported element");
        }
    }

    static void validateTags(Element element) {
        var tags = children(element, "tag");
        for (Element tag : tags) {
            attributes(tag, Set.of());
        }
        if (children(element, null).size() != tags.size()) {
            throw new IllegalArgumentException("operation tags contain an unsupported element");
        }
    }

    static void validateAliases(Element element) {
        var aliases = children(element, "alias");
        if (aliases.size() > MAX_ALIASES) {
            throw new IllegalArgumentException("operation aliases exceed their count bound");
        }
        for (Element alias : aliases) {
            attributes(alias, Set.of("tool", "action", "actionless"));
        }
        if (children(element, null).size() != aliases.size()) {
            throw new IllegalArgumentException("operation aliases contain an unsupported element");
        }
    }

    static void validateBounds(Node node, int depth) {
        Deque<Node> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        Deque<Boolean> countText = new ArrayDeque<>();
        nodes.push(node);
        depths.push(depth);
        countText.push(true);
        int elements = 0;
        int payloadBytes = 0;
        Map<Node, Integer> exampleBytes = new IdentityHashMap<>();
        while (!nodes.isEmpty()) {
            Node current = nodes.pop();
            int currentDepth = depths.pop();
            boolean countCurrentText = countText.pop();
            if (currentDepth > MAX_DEPTH) {
                throw new IllegalArgumentException("operation manifest XML nesting is too deep");
            }
            if (current.getNodeType() == Node.DOCUMENT_TYPE_NODE
                    || current.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
                throw new IllegalArgumentException("operation manifest DTD or entity is disabled");
            }
            if (current instanceof Element) {
                elements++;
                if (elements > MAX_ELEMENTS) {
                    throw new IllegalArgumentException("operation manifest contains too many elements");
                }
            }
            payloadBytes += attributeBytes(current);
            payloadBytes += checkedTextBytes(current, countCurrentText, exampleBytes);
            if (payloadBytes > OperationManifestLoader.MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("operation manifest payload exceeds its byte bound");
            }
            if (current instanceof Element element) {
                pushAttributes(element, currentDepth, nodes, depths, countText);
            }
            for (Node child = current.getFirstChild(); child != null; child = child.getNextSibling()) {
                nodes.push(child);
                depths.push(currentDepth + 1);
                countText.push(countCurrentText);
            }
        }
    }

    static int attributeBytes(Node node) {
        if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
            return node.getNodeName().getBytes(StandardCharsets.UTF_8).length
                    + node.getNodeValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (node.getNodeType() == Node.COMMENT_NODE || node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
            return node.getNodeValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return 0;
    }

    static int checkedTextBytes(Node node, boolean countText, Map<Node, Integer> exampleBytes) {
        if (!countText || (node.getNodeType() != Node.TEXT_NODE
                && node.getNodeType() != Node.CDATA_SECTION_NODE)) {
            return 0;
        }
        int bytes = node.getNodeValue().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("operation manifest text exceeds its byte bound");
        }
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent instanceof Element element && "example".equals(element.getTagName())) {
                int total = exampleBytes.merge(parent, bytes, Integer::sum);
                if (total > MAX_TEXT_BYTES) {
                    throw new IllegalArgumentException("operation manifest example exceeds its byte bound");
                }
                break;
            }
        }
        return bytes;
    }

    static void pushAttributes(Element element, int depth, Deque<Node> nodes,
            Deque<Integer> depths, Deque<Boolean> countText) {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            nodes.push(element.getAttributes().item(index));
            depths.push(depth + 1);
            countText.push(false);
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
