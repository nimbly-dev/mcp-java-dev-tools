package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationDirectoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void loadsTheVersionedBuiltInManifestAndKeepsEveryAliasUnique() {
        OperationManifestDocument document = OperationManifestLoader.loadBuiltIn();

        assertThat(document.version()).isEqualTo(1);
        assertThat(document.operations()).hasSize(54);
        assertThat(document.operations().values()).allSatisfy(operation ->
                assertThat(operation.aliases()).isNotEmpty());
        assertThat(document.documentation(OperationId.of("probe.status")).safety().sideEffect())
                .isEqualTo("probe_endpoint_read");
    }

    @Test
    void joinsDocumentationToTypedSchemasAndExecutesWithoutDescribe() throws Exception {
        OperationDirectory directory = directory("demo.echo", false, false, 60_000,
                request -> new EchoResult(((EchoRequest) request).message()), EchoResult.class,
                documentation("demo.echo", "none", false, null,
                        List.of(new OperationArgumentDocumentation(
                                "message", "text to echo", "string", true, null)),
                        JSON.readTree("{\"message\":\"hello\"}")));

        OperationDescriptor descriptor = directory.describe(OperationId.of("demo.echo"));
        OperationExecutionResult result = directory.execute(
                new OperationInvocation(
                        OperationId.of("demo.echo"), JSON.readTree("{\"message\":\"hello\"}")));

        assertThat(descriptor.inputSchema().definition().path("properties").path("message").path("type")
                .asText()).isEqualTo("string");
        assertThat(descriptor.inputSchema().definition().path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("message").asText()).isEqualTo("hello");
        assertThat(directory.execute(OperationId.of("demo.echo"),
                JSON.readTree("{\"message\":\"hello\",\"unknown\":true}")).status())
                .isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(directory.traceInventory()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.operationId()).isEqualTo("demo.echo");
                    assertThat(trace.compatibility()).containsEntry(
                            "normalization", "legacy_tool_action_to_canonical_operation_id");
                });
    }

    @Test
    void catalogSearchFiltersOrdersAndPaginatesWithAnOpaqueCursor() throws Exception {
        OperationDirectory directory = new OperationDirectory(
                List.of(
                        registration("demo.beta", request -> new EchoResult("beta"), EchoResult.class,
                                documentation("demo.beta", "none", false, null, echoArguments(),
                                        JSON.readTree("{\"message\":\"beta\"}"))),
                        registration("demo.alpha", request -> new EchoResult("alpha"), EchoResult.class,
                                documentation("demo.alpha", "none", false, null, echoArguments(),
                                        JSON.readTree("{\"message\":\"alpha\"}")))),
                document(
                        documentation("demo.beta", "none", false, null, echoArguments(),
                                JSON.readTree("{\"message\":\"beta\"}")),
                        documentation("demo.alpha", "none", false, null, echoArguments(),
                                JSON.readTree("{\"message\":\"alpha\"}"))));

        CatalogPage page = directory.catalog(new CatalogQuery(null, "demo", "demo", 1, null));

        assertThat(page.entries()).extracting(OperationCatalogEntry::operationId)
                .containsExactly(OperationId.of("demo.alpha"));
        assertThat(page.nextCursor()).isNotNull();
        assertThat(directory.catalog(new CatalogQuery(null, "demo", "demo", 1, page.nextCursor())).entries())
                .extracting(OperationCatalogEntry::operationId)
                .containsExactly(OperationId.of("demo.beta"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> directory.catalog(new CatalogQuery("other", "demo", "demo", 1,
                        page.nextCursor())))
                .withMessageContaining("catalog cursor is invalid");
        assertThat(new CatalogQuery(null, null, null, 0, null).limit())
                .isEqualTo(CatalogQuery.DEFAULT_LIMIT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQuery(null, null, null, CatalogQuery.MAX_LIMIT + 1, null))
                .withMessageContaining("catalog limit");
    }

    @Test
    void rejectsOrphanDocumentationInvalidExamplesAndUnsafeXml() throws Exception {
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.echo", request -> new EchoResult(((EchoRequest) request).message()), EchoResult.class,
                documentation("demo.echo", "none", false, null,
                        List.of(new OperationArgumentDocumentation(
                                "message", "text", "string", true, null)),
                        JSON.readTree("{\"message\":3}")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(List.of(registration),
                        document(documentation("demo.echo", "none", false, null,
                                List.of(new OperationArgumentDocumentation(
                                        "message", "text", "string", true, null)),
                                JSON.readTree("{\"message\":3}")))))
                .withMessageContaining("manifest example is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationDirectory(List.of(registration),
                        document(documentation("demo.other", "none", false, null, List.of(), JSON.readTree("{}")))))
                .withMessageContaining("missing operation documentation");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationManifestLoader().load(new ByteArrayInputStream(
                        "<!DOCTYPE operations [<!ENTITY x SYSTEM \"file:///secret\">]><operations manifestVersion=\"1\"/>"
                                .getBytes(StandardCharsets.UTF_8))))
                .withMessageContaining("operation manifest XML is invalid");
    }

    @Test
    void boundsManifestInputAndRejectsUnknownNestedAttributes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationManifestLoader().load(new ByteArrayInputStream(
                        new byte[OperationManifestLoader.MAX_MANIFEST_BYTES + 1])))
                .withMessageContaining("operation manifest XML is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationManifestLoader().load(new ByteArrayInputStream(
                        ("<operations manifestVersion=\"1\"><operation id=\"demo.echo\" "
                                + "classification=\"demo\"><summary unexpected=\"true\">Echo</summary>"
                                + "<description>Echoes text.</description><aliases><alias tool=\"demo\" "
                                + "action=\"echo\"/></aliases></operation></operations>")
                                .getBytes(StandardCharsets.UTF_8))))
                .withMessageContaining("unsupported attribute");
    }

    @Test
    void keepsJavaSafetyAuthoritativeWhenXmlOmitsSafety() {
        OperationManifestDocument document = new OperationManifestLoader().load(
                new ByteArrayInputStream(("<operations manifestVersion=\"1\">"
                        + "<operation id=\"demo.echo\" classification=\"demo\">"
                        + "<summary>Echo</summary><description>Echoes text.</description>"
                        + "<aliases><alias tool=\"demo\" action=\"echo\"/></aliases>"
                        + "</operation></operations>").getBytes(StandardCharsets.UTF_8)));

        assertThat(document.documentation(OperationId.of("demo.echo")).safety()).isNull();
    }

    @Test
    void replacesCuratedXmlSafetyWithTheJavaRegistrationPolicy() throws Exception {
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "echo", EchoRequest.class.getName(), EchoResult.class.getName(),
                EchoOperation.class.getName(),
                new OperationTraceMetadata(
                        "Adapter", "RequestMapper", "Feature", "ResponseMapper", "evidence",
                        "none", Map.of("owner", EchoOperation.class.getName())));
        OperationRegistration<EchoRequest, EchoResult> registration = new OperationRegistration<>(
                descriptor,
                EchoRequest.class,
                EchoResult.class,
                new OperationRegistrationContract(
                        echoSchema(), resultSchema(), OperationSafetyPolicy.legacy("none")),
                input -> JSON.convertValue(input, EchoRequest.class),
                request -> new EchoResult(request.message()),
                result -> JSON.valueToTree(result));
        OperationDocumentation documentation = documentation(
                "demo.echo", "none", true, null,
                List.of(new OperationArgumentDocumentation(
                        "message", "text", "string", true, null)),
                JSON.readTree("{\"message\":\"hello\"}"));

        OperationDescriptor assembled = new OperationDirectory(
                List.of(registration), document(documentation)).describe(OperationId.of("demo.echo"));

        assertThat(assembled.safety().confirmationRequired()).isFalse();
        assertThat(assembled.documentation().safety()).isEqualTo(assembled.safety());
    }

    @Test
    void validatesBoundedDraftSchemasAndResolvesLocalReferences() throws Exception {
        OperationSchema schema = new OperationSchema(JSON.readTree("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "$defs":{"message":{"type":"string","minLength":1}},
                  "type":"object",
                  "properties":{"message":{"$ref":"#/$defs/message"}},
                  "required":["message"],
                  "additionalProperties":false
                }
                """));

        assertThat(OperationSchemaValidator.violations(schema,
                JSON.readTree("{\"message\":\"hello\"}"))).isEmpty();
        assertThat(OperationSchemaValidator.violations(schema,
                JSON.readTree("{\"message\":3}"))).isNotEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(JSON.readTree("""
                        {"$schema":"https://json-schema.org/draft/2020-12/schema",
                         "$ref":"https://example.com/schema.json"}
                        """)))
                .withMessageContaining("references must be local");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(JSON.readTree("""
                        {
                          "$schema":"https://json-schema.org/draft/2020-12/schema",
                          "$defs":{"node":{"type":"object","properties":{"next":{"$ref":"#/$defs/node"}}}},
                          "type":"object","properties":{"node":{"$ref":"#/$defs/node"}}
                        }
                        """)))
                .withMessageContaining("recursive");
    }

    @Test
    void representsOpenJsonValuesOnlyThroughAnExplicitSchemaAndBinder() throws Exception {
        ObjectMapper mapper = JSON;
        OperationSchema schema = openValueSchema();
        OpenRequest request = new OpenRequest(mapper.readTree("{\"nested\":true}"));

        assertThat(OperationSchemaValidator.violations(schema,
                mapper.readTree("{\"value\":{\"nested\":true}}"))).isEmpty();
        assertThat(request.value().path("nested").asBoolean()).isTrue();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationSchemaValidator.requireValid(schema,
                        mapper.readTree("{\"value\":{\"nested\":true},\"unknown\":true}")))
                .withMessageContaining("is not supported");
    }

    @Test
    void validatesNullableObjectAndArrayUnionsAndRequiresTheSchemaDialect() throws Exception {
        OperationSchema nullableObject = new OperationSchema(JSON.readTree("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":["object","null"],
                  "properties":{"message":{"type":"string"}},
                  "required":["message"],
                  "additionalProperties":false
                }
                """));
        OperationSchema nullableArray = new OperationSchema(JSON.readTree("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":["array","null"],
                  "items":{"type":"string"}
                }
                """));

        assertThat(OperationSchemaValidator.violations(nullableObject,
                JSON.readTree("null"))).isEmpty();
        assertThat(OperationSchemaValidator.violations(nullableObject,
                JSON.readTree("{}"))).isNotEmpty();
        assertThat(OperationSchemaValidator.violations(nullableArray,
                JSON.readTree("[3]"))).isNotEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(JSON.readTree("{\"type\":\"string\"}")))
                .withMessageContaining("Draft 2020-12");
    }

    @Test
    void requiresManifestDefaultsToMatchJavaOwnedSchemaDefaults() throws Exception {
        OperationSchema schema = new OperationSchema(JSON.readTree("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "properties":{"enabled":{"type":"boolean","default":false}},
                  "additionalProperties":false
                }
                """));
        OperationId id = OperationId.of("demo.defaults");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.validateArguments(id,
                        List.of(new OperationArgumentDocumentation(
                                "enabled", "Whether it is enabled.", "boolean", false, null)),
                        schema))
                .withMessageContaining("default mismatch");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.validateArguments(id,
                        List.of(new OperationArgumentDocumentation(
                                "enabled", "Whether it is enabled.", "boolean", false,
                                JSON.readTree("true"))),
                        schema))
                .withMessageContaining("default mismatch");
        OperationManifestAssembler.validateArguments(id,
                List.of(new OperationArgumentDocumentation(
                        "enabled", "Whether it is enabled.", "boolean", false,
                        JSON.readTree("false"))), schema);
    }

    @Test
    void enforcesConfirmationDeprecationBoundsAndRedactsResults() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.secret", "filesystem_write", true, null, echoArguments(),
                JSON.readTree("{\"message\":\"secret\"}"));
        OperationRegistration<EchoRequest, SecretResult> registration = registration(
                "demo.secret", request -> new SecretResult("safe", "do-not-leak"), SecretResult.class,
                documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        assertThat(directory.execute(OperationId.of("demo.secret"),
                JSON.readTree("{\"message\":\"secret\"}")).status())
                .isEqualTo(OperationExecutionStatus.CONFIRMATION_REQUIRED);
        OperationExecutionResult result = directory.execute(new OperationInvocation(
                OperationId.of("demo.secret"), JSON.readTree("{\"message\":\"secret\"}"), true));
        assertThat(result.result().path("token").asText()).isEqualTo("***REDACTED***");
        assertThat(result.result().path("value").asText()).isEqualTo("safe");
    }

    @Test
    void returnsTimeoutAndDoesNotExposeExecutorFailureDetails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OperationDocumentation documentation = documentation(
                "demo.slow", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"slow\"}"), 10);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.slow", request -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return new EchoResult("done");
                }, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(OperationId.of("demo.slow"),
                JSON.readTree("{\"message\":\"slow\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
    }

    @Test
    void doesNotInterruptAnOperationWhenItsPolicyDisallowsCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        OperationDocumentation documentation = documentation(
                "demo.non_cancellable", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"wait\"}"), 10, false);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.non_cancellable", request -> {
                    started.countDown();
                    try {
                        release.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                    return new EchoResult("done");
                }, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(OperationId.of("demo.non_cancellable"),
                JSON.readTree("{\"message\":\"wait\"}"));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(interrupted).isFalse();
        release.countDown();
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private <O> OperationDirectory directory(
            String id,
            boolean confirmation,
            boolean deprecated,
            long timeout,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation) {
        OperationRegistration<EchoRequest, O> registration = registration(
                id, executor, resultType, documentation);
        return new OperationDirectory(List.of(registration), document(documentation));
    }

    private <O> OperationRegistration<EchoRequest, O> registration(
            String id,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation) {
        OperationDescriptor descriptor = new OperationDescriptor(
                id.substring(0, id.indexOf('.')),
                id.substring(id.indexOf('.') + 1),
                EchoRequest.class.getName(),
                resultType.getName(),
                OperationDirectoryTest.class.getName(),
                new OperationTraceMetadata(
                        "Adapter", "RequestMapper", "Feature", "ResponseMapper", "evidence",
                        documentation.safety().sideEffect(), Map.of("owner", "OperationDirectoryTest")));
        return new OperationRegistration<>(
                descriptor,
                EchoRequest.class,
                resultType,
                new OperationRegistrationContract(
                        echoSchema(),
                        resultSchema(),
                        documentation.safety()),
                input -> JSON.convertValue(input, EchoRequest.class),
                executor,
                result -> JSON.valueToTree(result));
    }

    private OperationSchema echoSchema() {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        root.putObject("properties").putObject("message").put("type", "string");
        root.putArray("required").add("message");
        root.put("additionalProperties", false);
        return new OperationSchema(root);
    }

    private OperationSchema resultSchema() {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("message").put("type", "string");
        properties.putObject("value").put("type", "string");
        properties.putObject("token").put("type", "string");
        root.put("additionalProperties", true);
        return new OperationSchema(root);
    }

    private OperationSchema openValueSchema() {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        var valueTypes = root.putObject("properties").putObject("value").putArray("type");
        valueTypes.add("object").add("array").add("string").add("integer")
                .add("number").add("boolean").add("null");
        root.putArray("required").add("value");
        root.put("additionalProperties", false);
        return new OperationSchema(root);
    }

    private OperationManifestDocument document(OperationDocumentation... documentation) {
        Map<OperationId, OperationDocumentation> values = new java.util.LinkedHashMap<>();
        for (OperationDocumentation entry : documentation) {
            values.put(OperationId.of(entry.summary()), entry);
        }
        return new OperationManifestDocument(1, values);
    }

    private List<OperationArgumentDocumentation> echoArguments() {
        return List.of(new OperationArgumentDocumentation(
                "message", "text to echo", "string", true, null));
    }

    private OperationDocumentation documentation(
            String id,
            String sideEffect,
            boolean confirmation,
            String replacement,
            List<OperationArgumentDocumentation> arguments,
            JsonNode example) {
        return documentation(id, sideEffect, confirmation, replacement, arguments, example, 60_000);
    }

    private OperationDocumentation documentation(
            String id,
            String sideEffect,
            boolean confirmation,
            String replacement,
            List<OperationArgumentDocumentation> arguments,
            JsonNode example,
            long timeout) {
        return documentation(id, sideEffect, confirmation, replacement, arguments, example, timeout, true);
    }

    private OperationDocumentation documentation(
            String id,
            String sideEffect,
            boolean confirmation,
            String replacement,
            List<OperationArgumentDocumentation> arguments,
            JsonNode example,
            long timeout,
            boolean cancellationSupported) {
        return new OperationDocumentation(
                id,
                "Documentation for " + id,
                id.substring(0, id.indexOf('.')),
                arguments,
                List.of(example),
                List.of(id.substring(0, id.indexOf('.'))),
                List.of(new OperationAlias(
                        id.substring(0, id.indexOf('.')), id.substring(id.indexOf('.') + 1))),
                "1",
                false,
                replacement,
                new OperationSafetyPolicy(
                        sideEffect, confirmation, "caller_must_not_supply_credentials",
                        "redact_sensitive_fields", timeout, cancellationSupported,
                        1_048_576, 4_194_304));
    }

    private record EchoRequest(String message) {
    }

    private record EchoResult(String message) {
    }

    private record OpenRequest(JsonNode value) {
    }

    private static final class EchoOperation implements Operation<DemoAction, EchoRequest, EchoResult> {

        private final OperationDescriptor descriptor;

        private EchoOperation(OperationDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public DemoAction operationId() {
            return DemoAction.ECHO;
        }

        @Override
        public OperationDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public String executableOwner() {
            return descriptor.executableOwner();
        }

        @Override
        public EchoResult execute(EchoRequest request) {
            return new EchoResult(request.message());
        }
    }

    private enum DemoAction {
        ECHO
    }

    private record SecretResult(String value, String token) {
    }
}
