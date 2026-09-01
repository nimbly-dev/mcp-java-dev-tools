package com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

class OperationManifestXmlSchemaValidatorTest {

    @Test
    void rejectsInternalDtdsBeforeSchemaCompilation() {
        String schema = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE xs:schema [<!ENTITY injected 'injected'>]>"
                + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                + "<xs:element name=\"&injected;\"/></xs:schema>";

        assertThatThrownBy(() -> OperationManifestXmlSchemaValidator.schema(
                schema.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDeepSchemasBeforeSchemaCompilation() {
        StringBuilder schema = new StringBuilder("<schema>");
        schema.append("<nested>".repeat(40));
        schema.append("</nested>".repeat(40));
        schema.append("</schema>");

        assertThatThrownBy(() -> OperationManifestXmlSchemaValidator.schema(
                schema.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting is too deep");
    }

    @Test
    void rejectsDomEntityResolutionAttempts() {
        assertThatThrownBy(() -> OperationManifestXmlSecurity.rejectingResolver()
                .resolveEntity("public-id", "https://example.invalid/entity.dtd"))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("external XML resolution is disabled");
    }

    @Test
    void rejectsExternalSchemaUrlsDuringCompilation() {
        String schema = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
                + "targetNamespace=\"urn:root\"><xs:import namespace=\"urn:external\" "
                + "schemaLocation=\"https://example.invalid/external.xsd\"/>"
                + "<xs:element name=\"root\" type=\"xs:string\"/></xs:schema>";

        assertThatThrownBy(() -> OperationManifestXmlSecurity.schema(
                schema.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external operation schema resolution is disabled");
    }
}
