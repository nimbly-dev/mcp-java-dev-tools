package com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml;

import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Shared bounded XML parsing and fail-closed schema security policy. */
public final class OperationManifestXmlSecurity {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELEMENTS = 4096;
    private static final int MAX_TEXT_BYTES = 65_536;
    /** Maximum encoded size of one capability-owned document. */
    public static final int MAX_CAPABILITY_BYTES = 262_144;
    private static final int DEPTH = 0;
    private static final int ELEMENTS = 1;
    private static final int TEXT_RUN_BYTES = 2;
    private static final int PAYLOAD_BYTES = 3;
    private static final int EXAMPLE_DEPTH = 4;
    private static final int EXAMPLE_BYTES = 5;
    private static final int SOURCE_BYTES = 6;

    private OperationManifestXmlSecurity() {
    }

    /** Runs bounded StAX preflight before any DOM or schema processing. */
    public static void preflight(byte[] source) {
        int[] bounds = new int[7];
        bounds[SOURCE_BYTES] = source.length;
        try (InputStream input = new ByteArrayInputStream(source)) {
            XMLStreamReader reader = xmlInputFactory().createXMLStreamReader(input);
            while (reader.hasNext()) {
                accept(reader.next(), reader, bounds);
            }
            reader.close();
        } catch (XMLStreamException | IOException exception) {
            throw new IllegalArgumentException("operation manifest XML is invalid", exception);
        }
    }

    /** Parses already bounded bytes with secure DOM settings and a rejecting resolver. */
    public static Document parse(byte[] source, Schema schema)
            throws ParserConfigurationException, IOException, SAXException {
        try {
            preflight(source);
        } catch (IllegalArgumentException exception) {
            throw new SAXException("operation manifest XML is invalid", exception);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setSchema(schema);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new OperationManifestXmlErrorHandler());
        builder.setEntityResolver(rejectingResolver());
        Document document = builder.parse(new ByteArrayInputStream(source));
        document.getDocumentElement().normalize();
        return document;
    }

    /** Compiles an XML schema only after bounded preflight and resolver hardening. */
    public static Schema schema(byte[] source) throws SAXException {
        preflight(source);
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
            throw new IllegalArgumentException("external operation schema resolution is disabled");
        });
        return factory.newSchema(new StreamSource(new ByteArrayInputStream(source)));
    }

    /** Returns the resolver used by secure DOM parsing; all resolution attempts fail closed. */
    public static EntityResolver rejectingResolver() {
        return (publicId, systemId) -> {
            throw new SAXException("external XML resolution is disabled");
        };
    }

    static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty("javax.xml.stream.isReplacingEntityReferences", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("external XML resolution is disabled");
        });
        return factory;
    }

    static void accept(int event, XMLStreamReader reader, int[] bounds) {
        if (event == XMLStreamConstants.START_ELEMENT) {
            startElement(reader, bounds);
        } else if (event == XMLStreamConstants.END_ELEMENT) {
            endElement(bounds);
        } else if (event == XMLStreamConstants.CHARACTERS
                || event == XMLStreamConstants.CDATA || event == XMLStreamConstants.SPACE) {
            addText(reader.getText(), bounds);
        } else if (event == XMLStreamConstants.COMMENT
                || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
            bounds[TEXT_RUN_BYTES] = 0;
            bounds[PAYLOAD_BYTES] += utf8Bytes(event == XMLStreamConstants.COMMENT
                    ? reader.getText() : reader.getPIData());
        } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new IllegalArgumentException("operation manifest DTD or entity is disabled");
        }
        validateBounds(bounds);
    }

    static void startElement(XMLStreamReader reader, int[] bounds) {
        bounds[DEPTH]++;
        bounds[ELEMENTS]++;
        bounds[TEXT_RUN_BYTES] = 0;
        if (bounds[DEPTH] == 1 && "tool".equals(reader.getLocalName())
                && bounds[SOURCE_BYTES] > MAX_CAPABILITY_BYTES) {
            throw new IllegalArgumentException("operation capability exceeds its byte bound");
        }
        bounds[PAYLOAD_BYTES] += attributeBytes(reader);
        if ("example".equals(reader.getLocalName())) {
            bounds[EXAMPLE_DEPTH] = bounds[DEPTH];
            bounds[EXAMPLE_BYTES] = 0;
        }
    }

    static void endElement(int[] bounds) {
        if (bounds[DEPTH] == bounds[EXAMPLE_DEPTH]) {
            bounds[EXAMPLE_DEPTH] = 0;
            bounds[EXAMPLE_BYTES] = 0;
        }
        bounds[DEPTH]--;
        bounds[TEXT_RUN_BYTES] = 0;
    }

    static void addText(String text, int[] bounds) {
        int bytes = utf8Bytes(text);
        bounds[TEXT_RUN_BYTES] += bytes;
        if (bounds[TEXT_RUN_BYTES] > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("operation manifest text exceeds its byte bound");
        }
        if (bounds[EXAMPLE_DEPTH] > 0) {
            bounds[EXAMPLE_BYTES] += bytes;
            if (bounds[EXAMPLE_BYTES] > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("operation manifest example exceeds its byte bound");
            }
        }
        bounds[PAYLOAD_BYTES] += bytes;
    }

    static int attributeBytes(XMLStreamReader reader) {
        int bytes = 0;
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            bytes += utf8Bytes(reader.getAttributeName(index).toString())
                    + utf8Bytes(reader.getAttributeValue(index));
        }
        return bytes;
    }

    static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static void validateBounds(int[] bounds) {
        if (bounds[DEPTH] > MAX_DEPTH) {
            throw new IllegalArgumentException("operation manifest XML nesting is too deep");
        }
        if (bounds[ELEMENTS] > MAX_ELEMENTS) {
            throw new IllegalArgumentException("operation manifest contains too many elements");
        }
        if (bounds[PAYLOAD_BYTES] > OperationManifestLoader.MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("operation manifest payload exceeds its byte bound");
        }
    }

}

/** Converts all non-fatal DOM parser diagnostics into deterministic failures. */
final class OperationManifestXmlErrorHandler extends DefaultHandler {

    @Override
    public void error(SAXParseException exception) throws SAXParseException {
        throw exception;
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXParseException {
        throw exception;
    }
}
