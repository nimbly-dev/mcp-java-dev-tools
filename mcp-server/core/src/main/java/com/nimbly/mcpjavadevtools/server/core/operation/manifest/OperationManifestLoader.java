package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlSecurity;
import org.w3c.dom.Element;

/** Secure loader for the versioned Core operation documentation manifest. */
public final class OperationManifestLoader {

    /** Current XML authoring format version. */
    public static final int CURRENT_VERSION = 1;
    /** Fixed classpath prefix containing built-in operation documentation resources. */
    static final String BUILT_IN_RESOURCE_PREFIX = "META-INF/mcpjvm/operations/";
    /** Classpath resource containing the built-in operation manifest index. */
    static final String BUILT_IN_INDEX_RESOURCE = BUILT_IN_RESOURCE_PREFIX + "index.xml";
    /** Classpath resource containing the built-in operation manifest index. */
    public static final String BUILT_IN_RESOURCE = BUILT_IN_INDEX_RESOURCE;
    /** Classpath resource containing the shared operation-document structure contract. */
    static final String BUILT_IN_SCHEMA_RESOURCE =
            BUILT_IN_RESOURCE_PREFIX + "operation-documents.xsd";
    /** Ordered, repository-owned capability resources allowed by the built-in index. */
    static final List<String> BUILT_IN_CAPABILITY_RESOURCES = List.of(
            "artifact_management.xml", "execution_orchestration.xml", "execution_profile_export.xml",
            "failure_analysis.xml", "jvm_lifecycle.xml", "performance_suite.xml", "probe.xml",
            "regression_suite.xml", "route_synthesis.xml", "security_suite.xml", "transport_execute.xml");
    /** Maximum accepted encoded XML manifest size. */
    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    /** Maximum number of entries in the built-in index. */
    static final int MAX_INDEX_ENTRIES = 32;
    /** Maximum encoded size of one built-in capability document. */
    static final int MAX_CAPABILITY_BYTES = OperationManifestXmlSecurity.MAX_CAPABILITY_BYTES;
    /** Maximum encoded size of the index and all capability documents together. */
    static final int MAX_AGGREGATE_BYTES = 2_097_152;
    /** Exact built-in operation count required by the aggregate Core directory. */
    static final int EXPECTED_BUILT_IN_OPERATION_COUNT = 54;

    /** Parses one XML manifest without closing the caller-owned stream. */
    public OperationManifestDocument load(InputStream source) {
        Objects.requireNonNull(source, "manifest input must not be null");
        try {
            byte[] boundedSource = readBounded(source, MAX_MANIFEST_BYTES);
            Document document = parse(new ByteArrayInputStream(boundedSource), null);
            Element root = document.getDocumentElement();
            if (root != null && "tool".equals(root.getTagName())) {
                return OperationManifestXmlReader.read(parse(
                        new ByteArrayInputStream(boundedSource),
                        schema(OperationManifestLoader.class.getClassLoader())));
            }
            if (root != null && "operation-manifest".equals(root.getTagName())) {
                ClassLoader resourceLoader = OperationManifestLoader.class.getClassLoader();
                Schema schema = schema(resourceLoader);
                List<String> resources = indexResources(parse(
                        new ByteArrayInputStream(boundedSource), schema));
                validateIndex(resources);
                return loadIndexedResources(resourceLoader, schema, boundedSource, resources);
            }
            return OperationManifestXmlReader.read(document);
        } catch (IOException | SAXException | ParserConfigurationException exception) {
            throw new IllegalArgumentException("operation manifest XML is invalid", exception);
        }
    }

    /** Loads the repository-owned manifest from the Core classpath. */
    public static OperationManifestDocument loadBuiltIn() {
        return new OperationManifestLoader().loadBuiltInResources(
                OperationManifestLoader.class.getClassLoader());
    }

    static OperationManifestDocument loadBuiltInResources(ClassLoader resourceLoader) {
        try {
            Schema schema = schema(resourceLoader);
            byte[] indexBytes = resource(resourceLoader, BUILT_IN_INDEX_RESOURCE, MAX_CAPABILITY_BYTES);
            List<String> resources = indexResources(parse(new ByteArrayInputStream(indexBytes), schema));
            validateIndex(resources);
            return loadIndexedResources(resourceLoader, schema, indexBytes, resources);
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new IllegalArgumentException("built-in operation manifest is invalid", exception);
        }
    }

    static OperationManifestDocument loadIndexedResources(ClassLoader resourceLoader,
            Schema schema, byte[] indexBytes, List<String> resources)
            throws IOException, ParserConfigurationException, SAXException {
        if (indexBytes.length > MAX_CAPABILITY_BYTES) {
            throw new IllegalArgumentException("operation manifest index exceeds its byte bound");
        }
        Map<OperationId, OperationDocumentation> operations = new LinkedHashMap<>();
        int aggregateBytes = indexBytes.length;
        for (String resourceName : resources) {
            byte[] capabilityBytes = resource(resourceLoader,
                    BUILT_IN_RESOURCE_PREFIX + resourceName, MAX_CAPABILITY_BYTES);
            aggregateBytes += capabilityBytes.length;
            if (aggregateBytes > MAX_AGGREGATE_BYTES) {
                throw new IllegalArgumentException("built-in operation documents exceed aggregate byte bound");
            }
            OperationManifestDocument fragment = OperationManifestXmlReader.read(
                    parse(new ByteArrayInputStream(capabilityBytes), schema));
            if (!fragment.operations().keySet().stream()
                    .allMatch(id -> id.api().equals(resourceName.substring(0, resourceName.length() - 4)))) {
                throw new IllegalArgumentException("operation resource filename does not match tool name");
            }
            merge(operations, fragment.operations());
        }
        if (operations.size() != EXPECTED_BUILT_IN_OPERATION_COUNT) {
            throw new IllegalArgumentException("built-in operation documents must contain exactly "
                    + EXPECTED_BUILT_IN_OPERATION_COUNT + " operations");
        }
        return new OperationManifestDocument(CURRENT_VERSION, operations);
    }

    static Schema schema(ClassLoader resourceLoader) throws SAXException, IOException {
        byte[] schemaBytes = resource(resourceLoader, BUILT_IN_SCHEMA_RESOURCE, MAX_CAPABILITY_BYTES);
        return OperationManifestXmlSecurity.schema(schemaBytes);
    }

    static byte[] resource(ClassLoader resourceLoader, String resourceName, int maximumBytes) throws IOException {
        String relativeName = resourceName.startsWith(BUILT_IN_RESOURCE_PREFIX)
                ? resourceName.substring(BUILT_IN_RESOURCE_PREFIX.length()) : "";
        if (relativeName.isBlank() || relativeName.equals(".") || relativeName.equals("..")
                || relativeName.contains("/") || relativeName.contains("\\") || relativeName.contains(":")
                || !resourceName.startsWith(BUILT_IN_RESOURCE_PREFIX)) {
            throw new IllegalArgumentException("built-in operation resource is outside its fixed prefix");
        }
        InputStream source = resourceLoader.getResourceAsStream(resourceName);
        if (source == null) {
            throw new IllegalArgumentException("built-in operation resource is missing: " + resourceName);
        }
        try (source) {
            return readBounded(source, maximumBytes);
        }
    }

    static void validateIndex(List<String> resources) {
        if (resources.size() > MAX_INDEX_ENTRIES || !resources.equals(
                resources.stream().sorted().toList())) {
            throw new IllegalArgumentException("built-in operation index is not canonical");
        }
        if (!resources.equals(BUILT_IN_CAPABILITY_RESOURCES)) {
            throw new IllegalArgumentException("built-in operation index resources are not approved");
        }
    }

    static void merge(Map<OperationId, OperationDocumentation> operations,
            Map<OperationId, OperationDocumentation> fragment) {
        for (Map.Entry<OperationId, OperationDocumentation> entry : fragment.entrySet()) {
            if (operations.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate operation documentation: "
                        + entry.getKey().value());
            }
        }
    }

    static Document parse(InputStream source, Schema schema)
            throws ParserConfigurationException, IOException, SAXException {
        byte[] boundedSource = readBounded(source, MAX_MANIFEST_BYTES);
        return OperationManifestXmlSecurity.parse(boundedSource, schema);
    }

    static byte[] readBounded(InputStream source, int maximumBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (read > maximumBytes - total) {
                throw new IOException("operation manifest exceeds its byte bound");
            }
            bytes.write(buffer, 0, read);
            total += read;
        }
        return bytes.toByteArray();
    }

    static List<String> indexResources(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !"operation-manifest".equals(root.getTagName())) {
            throw new IllegalArgumentException("operation manifest index root is invalid");
        }
        List<String> resources = new java.util.ArrayList<>();
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element resource && "resource".equals(resource.getTagName())) {
                String name = resource.getAttribute("name").trim();
                if (name.isBlank() || resource.getAttributes().getLength() != 1
                        || resource.getFirstChild() != null) {
                    throw new IllegalArgumentException("operation manifest index resource is invalid");
                }
                resources.add(name);
            } else if (node instanceof Element) {
                throw new IllegalArgumentException("operation manifest index contains an unsupported element");
            }
        }
        return List.copyOf(resources);
    }
}
