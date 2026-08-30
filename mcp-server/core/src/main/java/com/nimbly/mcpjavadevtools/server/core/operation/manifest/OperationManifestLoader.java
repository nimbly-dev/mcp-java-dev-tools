package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import java.io.StringReader;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader;

/** Secure loader for the versioned Core operation documentation manifest. */
public final class OperationManifestLoader {

    /** Current XML authoring format version. */
    public static final int CURRENT_VERSION = 1;
    /** Classpath resource containing the built-in Core operation documentation. */
    public static final String BUILT_IN_RESOURCE =
            "META-INF/mcpjvm/operations/manifest.xml";
    /** Maximum accepted encoded XML manifest size. */
    public static final int MAX_MANIFEST_BYTES = 1_048_576;

    /** Parses one XML manifest without closing the caller-owned stream. */
    public OperationManifestDocument load(InputStream source) {
        Objects.requireNonNull(source, "manifest input must not be null");
        try {
            return OperationManifestXmlReader.read(parse(source));
        } catch (IOException | SAXException | ParserConfigurationException exception) {
            throw new IllegalArgumentException("operation manifest XML is invalid", exception);
        }
    }

    /** Loads the repository-owned manifest from the Core classpath. */
    public static OperationManifestDocument loadBuiltIn() {
        InputStream source = OperationManifestLoader.class.getClassLoader()
                .getResourceAsStream(BUILT_IN_RESOURCE);
        if (source == null) {
            throw new IllegalArgumentException("built-in operation manifest is missing");
        }
        try (source) {
            return new OperationManifestLoader().load(source);
        } catch (IOException exception) {
            throw new IllegalArgumentException("built-in operation manifest could not be closed", exception);
        }
    }

    static Document parse(InputStream source)
            throws ParserConfigurationException, IOException, SAXException {
        byte[] boundedSource = readBounded(source);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new ByteArrayInputStream(boundedSource));
        document.getDocumentElement().normalize();
        return document;
    }

    static byte[] readBounded(InputStream source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (read > MAX_MANIFEST_BYTES - total) {
                throw new IOException("operation manifest exceeds its byte bound");
            }
            bytes.write(buffer, 0, read);
            total += read;
        }
        return bytes.toByteArray();
    }
}
