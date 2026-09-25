package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.net.URLClassLoader;
import javax.xml.parsers.DocumentBuilderFactory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlReader;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.xml.OperationManifestXmlSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

class OperationManifestFragmentTest {

    @Test
    void loadsTheOrderedCapabilityPartitionAsOneVersionedManifest() {
        OperationManifestDocument document = OperationManifestLoader.loadBuiltIn();

        assertThat(document.version()).isEqualTo(1);
        assertThat(OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES)
                .containsExactly(
                        "artifact_management.xml",
                        "execution_orchestration.xml",
                        "execution_profile_export.xml",
                        "failure_analysis.xml",
                        "jvm_lifecycle.xml",
                        "performance_suite.xml",
                        "probe.xml",
                        "regression_suite.xml",
                        "route_synthesis.xml",
                        "security_suite.xml",
                        "transport_execute.xml");
        assertThat(document.operations()).hasSize(54);
        assertThat(document.operations().keySet().stream()
                .collect(Collectors.groupingBy(OperationId::api, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("artifact_management", 31L),
                        Map.entry("execution_orchestration", 1L),
                        Map.entry("execution_profile_export", 1L),
                        Map.entry("failure_analysis", 2L),
                        Map.entry("jvm_lifecycle", 3L),
                        Map.entry("performance_suite", 1L),
                        Map.entry("probe", 7L),
                        Map.entry("regression_suite", 2L),
                        Map.entry("route_synthesis", 4L),
                        Map.entry("security_suite", 1L),
                        Map.entry("transport_execute", 1L)));
    }

    @Test
    void loadsTheIndexedEnvelopeThroughThePublicCompatibilityPairing() throws Exception {
        try (InputStream source = OperationManifestLoader.class.getClassLoader()
                .getResourceAsStream(OperationManifestLoader.BUILT_IN_RESOURCE)) {
            assertThat(source).isNotNull();
            assertThat(new OperationManifestLoader().load(source).operations()).hasSize(54);
        }
    }

    @Test
    void exposesOnlyTheIndexedCapabilityResources() throws Exception {
        ClassLoader loader = OperationManifestLoader.class.getClassLoader();

        assertThat(loader.getResource(OperationManifestLoader.BUILT_IN_INDEX_RESOURCE)).isNotNull();
        assertThat(loader.getResource(OperationManifestLoader.BUILT_IN_SCHEMA_RESOURCE)).isNotNull();
        for (String resource : OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES) {
            assertThat(loader.getResource("META-INF/mcpjvm/operations/" + resource))
                    .as("built-in capability resource %s", resource)
                    .isNotNull();
        }
        Path resourceDirectory = Path.of(loader.getResource(
                OperationManifestLoader.BUILT_IN_RESOURCE_PREFIX).toURI());
        try (Stream<Path> files = Files.list(resourceDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactlyElementsOf(Stream.concat(
                            Stream.of("index.xml", "operation-documents.xsd"),
                            OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES.stream())
                            .sorted().toList());
        }
    }

    @Test
    void rejectsDocumentsThatFailTheSharedSchema() throws Exception {
        String invalidIndex = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<operation-manifest formatVersion=\"999\" unexpected=\"x\">"
                + "<resource name=\"probe.xml\"/></operation-manifest>";

        assertThatThrownBy(() -> OperationManifestLoader.parse(
                new ByteArrayInputStream(invalidIndex.getBytes(StandardCharsets.UTF_8)),
                OperationManifestLoader.schema(OperationManifestLoader.class.getClassLoader())))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void appliesTheTextBoundToCdataContent() {
        String jsonString = "\"" + "x".repeat(65_535) + "\"";
        int split = jsonString.length() / 2;
        String capability = "<tool name=\"probe\" formatVersion=\"2\">"
                + "<action name=\"status\" classification=\"runtime\">"
                + "<summary>summary</summary><description>description</description>"
                + "<examples><example><![CDATA[" + jsonString.substring(0, split) + "]]><![CDATA["
                + jsonString.substring(split) + "]]></example></examples>"
                + "</action></tool>";

        assertThatThrownBy(() -> OperationManifestXmlReader.read(OperationManifestLoader.parse(
                new ByteArrayInputStream(capability.getBytes(StandardCharsets.UTF_8)),
                OperationManifestLoader.schema(OperationManifestLoader.class.getClassLoader()))))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void rejectsDeepDocumentsBeforeDomConstruction() {
        StringBuilder document = new StringBuilder("<operations manifestVersion=\"1\">");
        document.append("<operation id=\"probe.status\">");
        for (int index = 0; index < 40; index++) {
            document.append("<nested>");
        }
        for (int index = 0; index < 40; index++) {
            document.append("</nested>");
        }
        document.append("</operation></operations>");

        assertThatThrownBy(() -> new OperationManifestLoader().load(
                new ByteArrayInputStream(document.toString().getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML is invalid");
    }

    @Test
    void rejectsInterElementTextThatExceedsTheBound() {
        String capability = "<tool name=\"probe\" formatVersion=\"2\">"
                + " ".repeat(65_537)
                + "<action name=\"status\" classification=\"runtime\">"
                + "<summary>summary</summary><description>description</description>"
                + "</action></tool>";

        assertThatThrownBy(() -> new OperationManifestLoader().load(
                new ByteArrayInputStream(capability.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML is invalid");
    }

    @Test
    void enforcesTheUtf8TextBoundAtTheExactBoundary() throws Exception {
        String exact = "<root>" + "é".repeat(32_768) + "</root>";
        assertThat(OperationManifestLoader.parse(new ByteArrayInputStream(
                exact.getBytes(StandardCharsets.UTF_8)), null)).isNotNull();

        String over = "<root>" + "é".repeat(32_768) + "é</root>";
        assertThatThrownBy(() -> OperationManifestLoader.parse(new ByteArrayInputStream(
                over.getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void preservesLegacyUtf16StreamCompatibility() throws Exception {
        String legacy = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
                + "<operations manifestVersion=\"1\"><operation id=\"probe.status\" "
                + "classification=\"runtime\"><summary>summary</summary>"
                + "<description>description</description></operation></operations>";

        assertThat(new OperationManifestLoader().load(new ByteArrayInputStream(
                legacy.getBytes(StandardCharsets.UTF_16))).operations()).hasSize(1);
    }

    @Test
    void enforcesTheElementCountAtTheExactBoundary() throws Exception {
        String exact = "<root>" + "<x/>".repeat(4_095) + "</root>";
        assertThat(OperationManifestLoader.parse(new ByteArrayInputStream(
                exact.getBytes(StandardCharsets.UTF_8)), null)).isNotNull();

        String over = "<root>" + "<x/>".repeat(4_096) + "</root>";
        assertThatThrownBy(() -> OperationManifestLoader.parse(new ByteArrayInputStream(
                over.getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void rejectsDtdsDuringSchemaCompilation() {
        String schema = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE xs:schema [<!ENTITY injected 'injected'>]>"
                + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                + "<xs:element name=\"&injected;\"/></xs:schema>";

        assertThatThrownBy(() -> OperationManifestXmlSecurity.schema(
                schema.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingDuplicateAndReorderedIndexEntries() {
        List<String> missing = new ArrayList<>(OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES);
        missing.removeLast();
        List<String> duplicate = new ArrayList<>(OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES);
        duplicate.set(1, duplicate.getFirst());
        List<String> reordered = new ArrayList<>(OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES);
        String first = reordered.getFirst();
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);

        assertThatThrownBy(() -> OperationManifestLoader.validateIndex(missing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationManifestLoader.validateIndex(duplicate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationManifestLoader.validateIndex(reordered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesCapabilityAndAggregateByteBounds() throws Exception {
        assertThat(OperationManifestLoader.readBounded(
                new ByteArrayInputStream(new byte[16]), 16)).hasSize(16);
        assertThatThrownBy(() -> OperationManifestLoader.readBounded(
                new ByteArrayInputStream(new byte[16]), 15))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDtdAndEntityInput() {
        String document = "<!DOCTYPE tool [<!ENTITY value 'summary'>]>"
                + "<tool name=\"probe\" formatVersion=\"2\"><action name=\"status\" "
                + "classification=\"runtime\"><summary>&value;</summary>"
                + "<description>description</description></action></tool>";

        assertThatThrownBy(() -> new OperationManifestLoader().load(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCapabilityIdentityThatCannotBeRepresentedByItsTool() {
        String capability = "<tool name=\"probe\" formatVersion=\"2\">"
                + "<action name=\"other.status\" classification=\"runtime\">"
                + "<summary>summary</summary><description>description</description></action></tool>";

        assertThatThrownBy(() -> new OperationManifestLoader().load(
                new ByteArrayInputStream(capability.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnvalidatedPublicCapabilityDomReads() throws Exception {
        String capability = "<tool name=\"probe\" formatVersion=\"2\" unknown=\"x\">"
                + "<action name=\"status\" classification=\"runtime\"><summary>s</summary>"
                + "<description>d</description><ignored/></action></tool>";

        assertThatThrownBy(() -> OperationManifestXmlReader.read(directDocument(capability)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesSecurityBoundsToDirectPublicCapabilityDomReads() throws Exception {
        String oversized = "<tool name=\"probe\" formatVersion=\"2\">"
                + " ".repeat(65_537)
                + "<action name=\"status\" classification=\"runtime\">"
                + "<summary>summary</summary><description>description</description>"
                + "</action></tool>";
        String dtd = "<!DOCTYPE tool [<!ENTITY value 'summary'>]>"
                + "<tool name=\"probe\" formatVersion=\"2\"><action name=\"status\" "
                + "classification=\"runtime\"><summary>&value;</summary>"
                + "<description>description</description></action></tool>";
        String oversizedAttribute = "<tool name=\"probe\" formatVersion=\"2\"><action "
                + "name=\"status\" classification=\"runtime\"><summary>s</summary>"
                + "<description d=\"" + "x".repeat(1_048_577) + "\">d</description></action></tool>";
        String oversizedMiscellaneous = "<tool name=\"probe\" formatVersion=\"2\"><action "
                + "name=\"status\" classification=\"runtime\"><summary>s</summary>"
                + "<description>d</description><!--" + "x".repeat(524_290)
                + "--><?processing " + "x".repeat(524_290) + "?></action></tool>";
        Document entityAttribute = directDocument(
                "<tool name=\"probe\" formatVersion=\"2\"><action name=\"status\" "
                        + "classification=\"runtime\"><summary>s</summary><description>d</description>"
                        + "</action></tool>");
        var action = (org.w3c.dom.Element) entityAttribute.getElementsByTagName("action").item(0);
        var name = entityAttribute.createAttribute("name");
        name.appendChild(entityAttribute.createTextNode("status"));
        name.appendChild(entityAttribute.createEntityReference("unresolved"));
        action.setAttributeNode(name);

        assertThatThrownBy(() -> OperationManifestXmlReader.read(directDocument(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text exceeds");
        assertThatThrownBy(() -> OperationManifestXmlReader.read(directDocument(dtd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DTD or entity");
        assertThatThrownBy(() -> OperationManifestXmlReader.read(entityAttribute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DTD or entity");
        assertThatThrownBy(() -> OperationManifestXmlReader.read(directDocument(oversizedAttribute)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload exceeds");
        assertThatThrownBy(() -> OperationManifestXmlReader.read(directDocument(oversizedMiscellaneous)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload exceeds");
    }

    @Test
    void rejectsResourceTraversalAndDuplicateCapabilityIds() throws Exception {
        assertThatThrownBy(() -> OperationManifestLoader.resource(
                OperationManifestLoader.class.getClassLoader(),
                OperationManifestLoader.BUILT_IN_RESOURCE_PREFIX + "../probe.xml", 100))
                .isInstanceOf(IllegalArgumentException.class);

        String duplicate = "<tool name=\"probe\" formatVersion=\"2\">"
                + "<action name=\"status\" classification=\"runtime\"><summary>s</summary>"
                + "<description>d</description></action>"
                + "<action name=\"status\" classification=\"runtime\"><summary>s</summary>"
                + "<description>d</description></action></tool>";
        assertThatThrownBy(() -> OperationManifestXmlReader.read(OperationManifestLoader.parse(
                new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8)),
                OperationManifestLoader.schema(OperationManifestLoader.class.getClassLoader()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate operation documentation");
    }

    @Test
    void enforcesTheProductionTextBoundPerExample() {
        String fortyKilobyteJson = "\"" + "x".repeat(39_998) + "\"";
        assertThat(loadCapability(examples(fortyKilobyteJson, fortyKilobyteJson)).operations())
                .hasSize(1);

        String exactJson = "\"" + "x".repeat(65_534) + "\"";
        assertThat(loadCapability(examples(exactJson)).operations()).hasSize(1);

        String overJson = "\"" + "x".repeat(65_535) + "\"";
        assertThatThrownBy(() -> loadCapability(examples(overJson)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("operation manifest text exceeds its byte bound");
    }

    @Test
    void enforcesTheExampleBoundAcrossSeparateTextNodes() {
        String first = "\"" + "x".repeat(32_767);
        String exactSecond = "x".repeat(32_767) + "\"";
        assertThat(loadCapability(splitExample(first, exactSecond)).operations()).hasSize(1);

        String overSecond = "x".repeat(32_768) + "\"";
        assertThatThrownBy(() -> loadCapability(splitExample(first, overSecond)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("operation manifest example exceeds its byte bound");
    }

    @Test
    void enforcesThePublicCapabilityByteCeiling() {
        byte[] source = capabilityDocument("").getBytes(StandardCharsets.UTF_8);
        assertThat(new OperationManifestLoader().load(new ByteArrayInputStream(
                padded(source, OperationManifestXmlSecurity.MAX_CAPABILITY_BYTES))).operations())
                .hasSize(1);
        assertThatThrownBy(() -> new OperationManifestLoader().load(new ByteArrayInputStream(
                padded(source, OperationManifestXmlSecurity.MAX_CAPABILITY_BYTES + 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("operation capability exceeds its byte bound");
    }

    @Test
    void enforcesProductionDepthAndSchemaCollectionBoundaries() throws Exception {
        assertThat(OperationManifestLoader.parse(new ByteArrayInputStream(
                nestedDocument(31).getBytes(StandardCharsets.UTF_8)), null)).isNotNull();
        assertThatThrownBy(() -> OperationManifestLoader.parse(new ByteArrayInputStream(
                nestedDocument(32).getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("XML is invalid");

        assertThat(loadCapability(arguments(128)).operations()).hasSize(1);
        assertThatThrownBy(() -> loadCapability(arguments(129)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(loadCapability(aliases(16)).operations()).hasSize(1);
        assertThatThrownBy(() -> loadCapability(aliases(17)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(loadCapability(repeatedExamples(16)).operations()).hasSize(1);
        assertThatThrownBy(() -> loadCapability(repeatedExamples(17)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesTheProductionOperationCountAndResourceIdentity(@TempDir Path temporaryDirectory) throws Exception {
        Path countRoot = temporaryDirectory.resolve("count");
        Path countResources = copyResources(countRoot);
        Path probe = countResources.resolve("probe.xml");
        String probeDocument = Files.readString(probe);
        int closingTool = probeDocument.lastIndexOf("</tool>");
        String extraAction = "<action name=\"extra\" classification=\"runtime\"><summary>s</summary>"
                + "<description>d</description></action>";
        Files.writeString(probe, probeDocument.substring(0, closingTool) + extraAction
                + probeDocument.substring(closingTool));

        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[] {countRoot.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> OperationManifestLoader.loadBuiltInResources(resources))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly 54 operations");
        }

        Path missingRoot = temporaryDirectory.resolve("missing");
        Path missingResources = copyResources(missingRoot);
        Files.delete(missingResources.resolve("probe.xml"));
        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[] {missingRoot.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> OperationManifestLoader.loadBuiltInResources(resources))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resource is missing");
        }

        Path wrongRoot = temporaryDirectory.resolve("wrong");
        Path wrongResources = copyResources(wrongRoot);
        Path wrongProbe = wrongResources.resolve("probe.xml");
        Files.writeString(wrongProbe, Files.readString(wrongProbe).replace(
                "<tool name=\"probe\"", "<tool name=\"other\""));
        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[] {wrongRoot.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> OperationManifestLoader.loadBuiltInResources(resources))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("filename does not match tool name");
        }
    }

    @Test
    void rejectsXIncludeAndExternalSchemaHintsOnTheProductionPath() {
        String capability = "<tool name=\"probe\" formatVersion=\"2\" "
                + "xmlns:xi=\"http://www.w3.org/2001/XInclude\"><action name=\"status\" "
                + "classification=\"runtime\"><summary>s</summary><description>d</description>"
                + "<xi:include href=\"https://example.invalid/operation.xml\"/></action></tool>";

        assertThatThrownBy(() -> new OperationManifestLoader().load(new ByteArrayInputStream(
                capability.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesTheProductionCapabilityByteCeiling(@TempDir Path temporaryDirectory) throws Exception {
        Path resourceDirectory = copyResources(temporaryDirectory);
        Path artifact = resourceDirectory.resolve("artifact_management.xml");
        Files.write(artifact, padded(Files.readAllBytes(artifact),
                OperationManifestLoader.MAX_CAPABILITY_BYTES + 1));

        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[] {temporaryDirectory.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> OperationManifestLoader.loadBuiltInResources(resources))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("built-in operation manifest is invalid");
        }
    }

    @Test
    void enforcesTheProductionAggregateByteCeiling(@TempDir Path temporaryDirectory) throws Exception {
        Path resourceDirectory = copyResources(temporaryDirectory);
        List<String> capabilities = OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES;
        int remainingBytes = capabilities.subList(8, capabilities.size()).stream()
                .mapToInt(name -> readSize(resourceDirectory.resolve(name))).sum();
        int variableTarget = OperationManifestLoader.MAX_AGGREGATE_BYTES
                - readSize(resourceDirectory.resolve("index.xml"))
                - (7 * OperationManifestLoader.MAX_CAPABILITY_BYTES) - remainingBytes;
        for (String resource : capabilities.subList(0, 7)) {
            Path file = resourceDirectory.resolve(resource);
            Files.write(file, padded(Files.readAllBytes(file), OperationManifestLoader.MAX_CAPABILITY_BYTES));
        }
        Path variable = resourceDirectory.resolve(capabilities.get(7));
        byte[] variableSource = Files.readAllBytes(variable);
        Files.write(variable, padded(variableSource, variableTarget));

        try (URLClassLoader resources = new URLClassLoader(
                new java.net.URL[] {temporaryDirectory.toUri().toURL()}, null)) {
            assertThat(OperationManifestLoader.loadBuiltInResources(resources).operations()).hasSize(54);
            Files.write(variable, padded(variableSource, variableTarget + 1));
            assertThatThrownBy(() -> OperationManifestLoader.loadBuiltInResources(resources))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("aggregate byte bound");
        }
    }

    private static Path copyResources(Path temporaryDirectory) throws IOException {
        Path directory = temporaryDirectory.resolve("META-INF/mcpjvm/operations");
        Files.createDirectories(directory);
        List<String> resources = Stream.concat(
                Stream.of("index.xml", "operation-documents.xsd"),
                OperationManifestLoader.BUILT_IN_CAPABILITY_RESOURCES.stream()).toList();
        for (String resource : resources) {
            try (InputStream source = OperationManifestLoader.class.getClassLoader().getResourceAsStream(
                    "META-INF/mcpjvm/operations/" + resource)) {
                Files.write(directory.resolve(resource), source.readAllBytes());
            }
        }
        return directory;
    }

    private static OperationManifestDocument loadCapability(String content) {
        return new OperationManifestLoader().load(new ByteArrayInputStream(
                capabilityDocument(content).getBytes(StandardCharsets.UTF_8)));
    }

    private static String capabilityDocument(String content) {
        return "<tool name=\"probe\" formatVersion=\"2\"><action name=\"status\" "
                + "classification=\"runtime\"><summary>summary</summary>"
                + "<description>description</description>" + content + "</action></tool>";
    }

    private static String arguments(int count) {
        StringBuilder arguments = new StringBuilder("<arguments>");
        for (int index = 0; index < count; index++) {
            arguments.append("<argument name=\"arg").append(index)
                    .append("\" description=\"description\" type=\"string\" required=\"false\"/>");
        }
        return arguments.append("</arguments>").toString();
    }

    private static String aliases(int count) {
        StringBuilder aliases = new StringBuilder("<aliases>");
        for (int index = 0; index < count; index++) {
            aliases.append("<alias tool=\"probe\" action=\"alias").append(index).append("\"/>");
        }
        return aliases.append("</aliases>").toString();
    }

    private static String examples(String... values) {
        StringBuilder examples = new StringBuilder("<examples>");
        for (String value : values) {
            examples.append("<example>").append(value).append("</example>");
        }
        return examples.append("</examples>").toString();
    }

    private static String splitExample(String first, String second) {
        return "<examples><example><![CDATA[" + first + "]]><!--split--><![CDATA["
                + second + "]]></example></examples>";
    }

    private static String repeatedExamples(int count) {
        String[] values = new String[count];
        java.util.Arrays.fill(values, "{}");
        return examples(values);
    }

    private static String nestedDocument(int nestedElements) {
        return "<root>" + "<nested>".repeat(nestedElements)
                + "</nested>".repeat(nestedElements) + "</root>";
    }

    private static byte[] padded(byte[] source, int targetSize) {
        String content = new String(source, StandardCharsets.UTF_8);
        int closingTag = content.lastIndexOf("</tool>");
        int commentBytes = targetSize - source.length;
        String padding = "<!--" + "x".repeat(commentBytes - 7) + "-->";
        return (content.substring(0, closingTag) + padding + content.substring(closingTag))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static int readSize(Path file) {
        try {
            return Math.toIntExact(Files.size(file));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Document directDocument(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
