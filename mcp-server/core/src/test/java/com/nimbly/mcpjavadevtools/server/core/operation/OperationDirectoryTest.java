package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationRequestDecoder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationArgumentDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationAlias;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestAssembler;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.concurrent.locks.LockSupport;

class OperationDirectoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void loadsTheVersionedBuiltInManifestAndKeepsEveryAliasUnique() {
        OperationManifestDocument document = OperationManifestLoader.loadBuiltIn();

        assertThat(document.version()).isEqualTo(1);
        assertThat(document.operations()).hasSize(54);
        assertThat(document.operations().values())
                .filteredOn(operation -> !operation.aliases().isEmpty())
                .hasSize(50);
        assertThat(document.operations().values())
                .filteredOn(operation -> operation.aliases().isEmpty())
                .extracting(operation -> operation.tags())
                .allSatisfy(tags -> assertThat(tags).contains("suite"));
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
                            "normalization", "released_tool_action_to_canonical_operation_id");
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
                OperationRequestDecoders.typed(JSON, EchoRequest.class),
                request -> new EchoResult(request.message()),
                OperationResultEncoders.typed(JSON, EchoResult.class));
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
                JSON.readTree("{\"message\":\"slow\"}"), 100);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.slow", request -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(250);
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
                JSON.readTree("{\"message\":\"wait\"}"), 100, false);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.non_cancellable", boundedNonCancellable(request -> {
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
                }), EchoResult.class, documentation);
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

    @Test
    void snapshotsInputBeforeAsynchronousBinding() throws Exception {
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        OperationDocumentation documentation = documentation(
                "demo.snapshot", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"original\"}"));
        OperationRequestDecoder<EchoRequest> decoder = input -> {
            decoderEntered.countDown();
            try {
                if (!releaseDecoder.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test decoder was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test decoder was interrupted", exception);
            }
            return new EchoRequest(input.path("message").asText());
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.snapshot", decoder, request -> new EchoResult(request.message()),
                EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));
        ObjectNode source = JSON.createObjectNode().put("message", "original");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<OperationExecutionResult> future = caller.submit(() -> directory.execute(
                    new OperationInvocation(OperationId.of("demo.snapshot"), source)));
            assertThat(decoderEntered.await(1, TimeUnit.SECONDS)).isTrue();
            source.put("message", "mutated");
            releaseDecoder.countDown();

            OperationExecutionResult result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
            assertThat(result.result().path("message").asText()).isEqualTo("original");
        } finally {
            releaseDecoder.countDown();
            caller.shutdownNow();
            caller.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void snapshotsInvocationInputAtConstruction() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.construction_snapshot", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"original\"}"));
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.construction_snapshot", request -> new EchoResult(request.message()),
                EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));
        ObjectNode source = JSON.createObjectNode().put("message", "original");
        OperationInvocation invocation = new OperationInvocation(
                OperationId.of("demo.construction_snapshot"), source);
        source.put("message", "mutated");

        OperationExecutionResult result = directory.execute(invocation);

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(result.result().path("message").asText()).isEqualTo("original");
    }

    @Test
    void rejectsOversizedTypedResultBeforeMaterializingItsJsonTree() throws Exception {
        AtomicBoolean materialized = new AtomicBoolean();
        OperationDocumentation documentation = documentation(
                "demo.typed_limit", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"large\"}"));
        BoundedOperationResultEncoder<EchoResult> encoder = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(EchoResult result) {
                materialized.set(true);
                return JSON.valueToTree(result);
            }

            @Override
            public void write(EchoResult result, OutputStream output) throws IOException {
                byte[] chunk = new byte[1024];
                int remaining = 4_194_305;
                while (remaining > 0) {
                    int length = Math.min(remaining, chunk.length);
                    output.write(chunk, 0, length);
                    remaining -= length;
                }
            }
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.typed_limit", request -> new EchoResult("large"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.typed_limit"), JSON.readTree("{\"message\":\"large\"}"));

        assertThat(result.reasonCode()).isEqualTo("operation_output_too_large");
        assertThat(materialized).isFalse();
    }

    @Test
    void preservesDecimalPrecisionUntilSchemaValidation() throws Exception {
        OperationSchema boundedNumber = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"number", "maximum":1}
                """));
        OperationSafetyPolicy safety = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100,
                true, 256, 4_194_304);
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "precise", JsonNode.class.getName(), JsonNode.class.getName(),
                OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        OperationRegistration<JsonNode, JsonNode> registration = OperationRegistration.typed(
                descriptor, JsonNode.class, JsonNode.class,
                new OperationRegistrationContract(boundedNumber, OperationSchema.empty(), safety),
                JSON, request -> request);
        OperationDocumentation documentation = documentation(
                "demo.precise", "none", false, null, List.of(), JSON.readTree("1"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.precise"), DecimalNode.valueOf(
                        new BigDecimal("1.0000000000000000001")));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("operation_input_schema_invalid");
    }

    @Test
    void rejectsNonFiniteNumbersBeforeSnapshotEncoding() throws Exception {
        OperationSafetyPolicy safety = hardSafety();
        OperationRegistration<JsonNode, JsonNode> registration = jsonRegistration(
                "demo.non_finite", safety, ignored -> JSON.createObjectNode());
        OperationDocumentation documentation = documentation(
                "demo.non_finite", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.non_finite"), DoubleNode.valueOf(Double.NaN));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("operation_input_structure_invalid");
    }

    @Test
    void rejectsNonFiniteNumbersFromMapperBackedResults() throws Exception {
        OperationSafetyPolicy safety = hardSafety();
        AtomicReference<JsonNode> output = new AtomicReference<>();
        OperationRegistration<JsonNode, JsonNode> registration = jsonRegistration(
                "demo.non_finite_result", safety, ignored -> output.get());
        OperationDocumentation documentation = documentation(
                "demo.non_finite_result", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            output.set(DoubleNode.valueOf(value));
            OperationExecutionResult result = directory.execute(
                    OperationId.of("demo.non_finite_result"), JSON.createObjectNode());
            assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
            assertThat(result.reasonCode()).isEqualTo("operation_normalization_failed");
        }
    }

    @Test
    void rejectsNonFiniteNumbersFromMapperBackedPojoResults() throws Exception {
        AtomicReference<Double> output = new AtomicReference<>(Double.NaN);
        OperationDocumentation documentation = documentation(
                "demo.non_finite_pojo", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationRegistration<EchoRequest, NonFiniteResult> registration = registration(
                "demo.non_finite_pojo", request -> new NonFiniteResult(output.get()),
                NonFiniteResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            output.set(value);
            OperationExecutionResult result = directory.execute(
                    OperationId.of("demo.non_finite_pojo"), JSON.readTree("{\"message\":\"ok\"}"));
            assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
            assertThat(result.reasonCode()).isEqualTo("operation_normalization_failed");
        }
    }

    @Test
    void rejectsLossyTypedNumericBinding() throws Exception {
        OperationSafetyPolicy safety = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100,
                true, 256, 4_194_304);
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "lossless", Integer.class.getName(), JsonNode.class.getName(),
                OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        OperationRegistration<Integer, JsonNode> registration = new OperationRegistration<>(
                descriptor, Integer.class, JsonNode.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), safety),
                OperationRequestDecoders.wrap(JSON, Integer.class, input -> input.intValue()),
                value -> JSON.getNodeFactory().numberNode(value),
                OperationResultEncoders.typed(JSON, JsonNode.class));
        OperationDocumentation documentation = documentation(
                "demo.lossless", "none", false, null, List.of(), JSON.readTree("1.9"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.lossless"), JSON.readTree("1.9"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_binding_failed");
    }

    @Test
    void rejectsLossyBindingWhenTheDecoderMutatesItsInputTree() throws Exception {
        OperationSafetyPolicy safety = hardSafety();
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "mutating_lossy", JsonNode.class.getName(), JsonNode.class.getName(),
                OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        AtomicBoolean ownerCalled = new AtomicBoolean();
        OperationRequestDecoder<JsonNode> decoder = input -> {
            ((ObjectNode) input).put("amount", 1);
            return input;
        };
        OperationRegistration<JsonNode, JsonNode> registration = new OperationRegistration<>(
                descriptor, JsonNode.class, JsonNode.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), safety),
                OperationRequestDecoders.wrap(JSON, JsonNode.class, decoder),
                value -> {
                    ownerCalled.set(true);
                    return JSON.createObjectNode();
                },
                OperationResultEncoders.typed(JSON, JsonNode.class));
        OperationDocumentation documentation = documentation(
                "demo.mutating_lossy", "none", false, null, List.of(),
                JSON.readTree("{\"amount\":1.9}"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.mutating_lossy"), JSON.readTree("{\"amount\":1.9}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_binding_failed");
        assertThat(ownerCalled).isFalse();
    }

    @Test
    void rejectsNonNullFieldsAddedByCustomDecoders() throws Exception {
        OperationSafetyPolicy safety = hardSafety();
        AtomicBoolean ownerCalled = new AtomicBoolean();
        OperationRequestDecoder<JsonNode> decoder = input -> {
            ((ObjectNode) input).put("injected", true);
            return input;
        };
        OperationRegistration<JsonNode, JsonNode> registration = new OperationRegistration<>(
                new OperationDescriptor(
                        "demo", "injected", JsonNode.class.getName(), JsonNode.class.getName(),
                        OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                                "adapter", "mapper", "feature", "response", "test", "none",
                                Map.of("owner", "test"))),
                JsonNode.class,
                JsonNode.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), safety),
                OperationRequestDecoders.wrap(JSON, JsonNode.class, decoder),
                ignored -> {
                    ownerCalled.set(true);
                    return JSON.createObjectNode();
                },
                OperationResultEncoders.typed(JSON, JsonNode.class));
        OperationDocumentation documentation = documentation(
                "demo.injected", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), JSON);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.injected"), JSON.createObjectNode());

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_binding_failed");
        assertThat(ownerCalled).isFalse();
    }

    @Test
    void permitsDecoderAddedNullOnlyWhenTheInputSchemaAllowsNull() throws Exception {
        List<OperationArgumentDocumentation> arguments = List.of(
                new OperationArgumentDocumentation("message", "text", "string", true, null),
                new OperationArgumentDocumentation("extra", "optional text", "string", false, null));
        OperationDocumentation rejectedDocumentation = documentation(
                "demo.null_added_rejected", "none", false, null, arguments,
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationDocumentation acceptedDocumentation = documentation(
                "demo.null_added_allowed", "none", false, null, arguments,
                JSON.readTree("{\"message\":\"ok\"}"));

        OperationDirectory rejected = new OperationDirectory(
                List.of(nullFieldRegistration(
                        "demo.null_added_rejected", nullFieldSchema(false), rejectedDocumentation)),
                document(rejectedDocumentation));
        OperationDirectory accepted = new OperationDirectory(
                List.of(nullFieldRegistration(
                        "demo.null_added_allowed", nullFieldSchema(true), acceptedDocumentation)),
                document(acceptedDocumentation));

        OperationExecutionResult rejectedResult = rejected.execute(
                OperationId.of("demo.null_added_rejected"), JSON.readTree("{\"message\":\"ok\"}"));
        OperationExecutionResult acceptedResult = accepted.execute(
                OperationId.of("demo.null_added_allowed"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(rejectedResult.reasonCode()).isEqualTo("operation_binding_failed");
        assertThat(acceptedResult.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
    }

    @Test
    void requiresTypedDefaultDeclarationsToMatchCustomMapperResults() throws Exception {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(DefaultContainerRequest.class,
                new JsonDeserializer<>() {
                    @Override
                    public DefaultContainerRequest deserialize(
                            JsonParser parser, DeserializationContext context) throws IOException {
                        parser.skipChildren();
                        return new DefaultContainerRequest(List.of("injected"));
                    }
                });
        ObjectMapper mapper = JSON.copy().registerModule(module);
        OperationSafetyPolicy safety = hardSafety();
        AtomicBoolean ownerCalled = new AtomicBoolean();
        OperationRegistration<DefaultContainerRequest, JsonNode> registration =
                new OperationRegistration<>(
                        new OperationDescriptor(
                                "demo", "typed_defaults", DefaultContainerRequest.class.getName(),
                                JsonNode.class.getName(), OperationDirectoryTest.class.getName(),
                                new OperationTraceMetadata(
                                        "adapter", "mapper", "feature", "response", "test", "none",
                                        Map.of("owner", "test"))),
                        DefaultContainerRequest.class,
                        JsonNode.class,
                        new OperationRegistrationContract(
                                OperationSchema.empty(), OperationSchema.empty(), safety),
                        OperationRequestDecoders.typedWithDefaults(
                                mapper, DefaultContainerRequest.class,
                                Map.of("items", mapper.createArrayNode())),
                        ignored -> {
                            ownerCalled.set(true);
                            return JSON.createObjectNode();
                        },
                        OperationResultEncoders.typed(mapper, JsonNode.class));
        OperationDocumentation documentation = documentation(
                "demo.typed_defaults", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation), mapper);

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.typed_defaults"), JSON.createObjectNode());

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_binding_failed");
        assertThat(ownerCalled).isFalse();
    }

    @Test
    void acceptsExactDefaultsOnlyWhenTheyAreOwnedByTheInputSchema() throws Exception {
        OperationDocumentation acceptedDocumentation = documentation(
                "demo.schema_default", "none", false, null,
                List.of(new OperationArgumentDocumentation(
                        "items", "optional items", "array", false, null)),
                JSON.createObjectNode());
        OperationDocumentation rejectedDocumentation = documentation(
                "demo.schema_forbidden_default", "none", false, null, List.of(),
                JSON.createObjectNode());
        OperationDirectory accepted = new OperationDirectory(
                List.of(defaultContainerRegistration(
                        "demo.schema_default", defaultContainerSchema(true), acceptedDocumentation)),
                document(acceptedDocumentation));
        OperationDirectory rejected = new OperationDirectory(
                List.of(defaultContainerRegistration(
                        "demo.schema_forbidden_default", defaultContainerSchema(false),
                        rejectedDocumentation)),
                document(rejectedDocumentation));

        OperationExecutionResult acceptedResult = accepted.execute(
                OperationId.of("demo.schema_default"), JSON.createObjectNode());
        OperationExecutionResult rejectedResult = rejected.execute(
                OperationId.of("demo.schema_forbidden_default"), JSON.createObjectNode());

        assertThat(acceptedResult.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(rejectedResult.reasonCode()).isEqualTo("operation_binding_failed");
    }

    @Test
    void enforcesExactOutputCeilingsThroughTheDirectory() throws Exception {
        int exactBytes = JSON.writeValueAsBytes(JSON.readTree(
                "{\"message\":\"exact\"}")).length;
        OperationSafetyPolicy safety = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100,
                true, 1_048_576, exactBytes);
        OperationDocumentation documentation = new OperationDocumentation(
                "demo.output_limit", "Documentation for output limit", "demo", echoArguments(),
                List.of(JSON.readTree("{\"message\":\"exact\"}")), List.of("demo"),
                List.of(new OperationAlias("demo", "output_limit")), "1", false, null, safety);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.output_limit", request -> new EchoResult(request.message()),
                EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult accepted = directory.execute(
                OperationId.of("demo.output_limit"), JSON.readTree("{\"message\":\"exact\"}"));
        OperationExecutionResult rejected = directory.execute(
                OperationId.of("demo.output_limit"), JSON.readTree("{\"message\":\"exactx\"}"));

        assertThat(accepted.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(rejected.reasonCode()).isEqualTo("operation_output_too_large");
    }

    @Test
    void enforcesExactInputCeilingsThroughTheDirectory() throws Exception {
        int exactBytes = JSON.writeValueAsBytes(JSON.readTree(
                "{\"message\":\"exact\"}")).length;
        OperationSafetyPolicy safety = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100,
                true, exactBytes, 4_194_304);
        OperationDocumentation documentation = new OperationDocumentation(
                "demo.input_limit", "Documentation for input limit", "demo", echoArguments(),
                List.of(JSON.readTree("{\"message\":\"exact\"}")), List.of("demo"),
                List.of(new OperationAlias("demo", "input_limit")), "1", false, null, safety);
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.input_limit", request -> new EchoResult(request.message()),
                EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult accepted = directory.execute(
                OperationId.of("demo.input_limit"), JSON.readTree("{\"message\":\"exact\"}"));
        OperationExecutionResult rejected = directory.execute(
                OperationId.of("demo.input_limit"), JSON.readTree("{\"message\":\"exactx\"}"));

        assertThat(accepted.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(rejected.reasonCode()).isEqualTo("operation_input_too_large");
    }

    @Test
    void enforcesTheActualHardPayloadCeilingsThroughTheDirectory() throws Exception {
        OperationSafetyPolicy safety = hardSafety();
        OperationDocumentation inputDocumentation = documentation(
                "demo.hard_input", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationRegistration<JsonNode, JsonNode> inputRegistration = jsonRegistration(
                "demo.hard_input", safety, ignored -> JSON.createObjectNode());
        OperationDirectory inputDirectory = new OperationDirectory(
                List.of(inputRegistration), document(inputDocumentation), JSON);
        ObjectNode exactInput = hardSizedObject(OperationSafetyLimits.MAX_INPUT_BYTES, 3);
        ObjectNode overInput = hardSizedObject(OperationSafetyLimits.MAX_INPUT_BYTES + 1, 3);

        OperationExecutionResult inputAccepted = inputDirectory.execute(
                OperationId.of("demo.hard_input"), exactInput);
        OperationExecutionResult inputRejected = inputDirectory.execute(
                OperationId.of("demo.hard_input"), overInput);

        assertThat(inputAccepted.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(inputRejected.reasonCode()).isEqualTo("operation_input_too_large");

        AtomicReference<JsonNode> output = new AtomicReference<>(
                hardSizedObject(OperationSafetyLimits.MAX_OUTPUT_BYTES, 15));
        OperationDocumentation outputDocumentation = documentation(
                "demo.hard_output", "none", false, null, List.of(), JSON.readTree("{}"));
        OperationRegistration<JsonNode, JsonNode> outputRegistration = jsonRegistration(
                "demo.hard_output", safety, ignored -> output.get());
        OperationDirectory outputDirectory = new OperationDirectory(
                List.of(outputRegistration), document(outputDocumentation), JSON);

        OperationExecutionResult outputAccepted = outputDirectory.execute(
                OperationId.of("demo.hard_output"), JSON.createObjectNode());
        output.set(hardSizedObject(OperationSafetyLimits.MAX_OUTPUT_BYTES + 1, 15));
        OperationExecutionResult outputRejected = outputDirectory.execute(
                OperationId.of("demo.hard_output"), JSON.createObjectNode());

        assertThat(outputAccepted.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(outputRejected.reasonCode()).isEqualTo("operation_output_too_large");
    }

    @Test
    void boundsDecoderDefaultsBeforeRetainingThem() throws Exception {
        ObjectNode exact = defaultValueAtDeclarationBytes(OperationSafetyLimits.MAX_INPUT_BYTES);
        assertThat(OperationRequestDecoders.typedWithDefaults(
                JSON, JsonNode.class, Map.of("value", exact))).isNotNull();

        ObjectNode over = defaultValueAtDeclarationBytes(OperationSafetyLimits.MAX_INPUT_BYTES + 1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationRequestDecoders.typedWithDefaults(
                        JSON, JsonNode.class, Map.of("value", over)))
                .withMessageContaining("input byte limit");
    }

    @Test
    void boundsDecoderDefaultMetadataNamesAndAggregateNodes() {
        String exactPath = "\uD83D\uDE00".repeat(OperationSafetyLimits.MAX_STRING_VALUE_BYTES / 4);
        assertThat(OperationRequestDecoders.typedWithDefaults(
                JSON, JsonNode.class, Map.of(exactPath, NullNode.getInstance()))).isNotNull();

        String overPath = "\uD83D\uDE00".repeat(OperationSafetyLimits.MAX_STRING_VALUE_BYTES / 4 + 1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationRequestDecoders.typedWithDefaults(
                        JSON, JsonNode.class, Map.of(overPath, NullNode.getInstance())))
                .withMessageContaining("JSON limits");

        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        for (int index = 0; index < OperationSafetyLimits.MAX_JSON_NODES; index++) {
            defaults.put("field" + index, NullNode.getInstance());
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationRequestDecoders.typedWithDefaults(
                        JSON, JsonNode.class, defaults))
                .withMessageContaining("JSON limits");
    }

    @Test
    void rejectsAResultNormalizerThatMutatesAndReturnsTheSameTree() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectNode shared = JSON.createObjectNode();
        OperationDocumentation documentation = documentation(
                "demo.mutable", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        BoundedOperationResultEncoder<EchoResult> encoder = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(EchoResult result) {
                return shared.put("counter", calls.incrementAndGet());
            }

            @Override
            public void write(EchoResult result, OutputStream output) throws IOException {
                JSON.writeValue(output, shared.put("counter", calls.incrementAndGet()));
            }
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.mutable", request -> new EchoResult("ok"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.mutable"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_normalization_failed");
    }

    @Test
    void rejectsTrailingJsonFromAnAuthoritativeResultStream() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.trailing_result", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        BoundedOperationResultEncoder<EchoResult> encoder = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(EchoResult result) {
                return JSON.createObjectNode();
            }

            @Override
            public void write(EchoResult result, OutputStream output) throws IOException {
                output.write("{} {}".getBytes(StandardCharsets.UTF_8));
            }
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.trailing_result", request -> new EchoResult("ok"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.trailing_result"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_normalization_failed");
    }

    @Test
    void rejectsTrailingJsonFromAnAuthoritativeRequestStream() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.trailing_request", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        BoundedOperationRequestDecoder<EchoRequest> decoder = new BoundedOperationRequestDecoder<>() {
            @Override
            public EchoRequest decode(JsonNode input) {
                return new EchoRequest(input.path("message").asText());
            }

            @Override
            public void write(EchoRequest request, OutputStream output) throws IOException {
                output.write("{} {}".getBytes(StandardCharsets.UTF_8));
            }
        };
        OperationRegistration<EchoRequest, EchoResult> registration = new OperationRegistration<>(
                new OperationDescriptor(
                        "demo", "trailing_request", EchoRequest.class.getName(),
                        EchoResult.class.getName(), OperationDirectoryTest.class.getName(),
                        new OperationTraceMetadata(
                                "adapter", "mapper", "feature", "response", "test", "none",
                                Map.of("owner", "test"))),
                EchoRequest.class,
                EchoResult.class,
                new OperationRegistrationContract(
                        echoSchema(), resultSchema(), documentation.safety()),
                decoder,
                request -> new EchoResult(request.message()),
                OperationResultEncoders.typed(JSON, EchoResult.class));
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.trailing_request"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_binding_failed");
    }

    @Test
    void rejectsUnboundedResultEncodersDuringDirectoryAssembly() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.unbounded", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.unbounded", request -> new EchoResult("ok"), EchoResult.class,
                documentation, result -> JSON.valueToTree(result));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        List.of(registration), document(documentation)))
                .withMessageContaining("bounded streaming");
    }

    @Test
    void boundsLegacyNormalizedStructureBeforeCopyingOrRepeating() throws Exception {
        AtomicInteger encodeCalls = new AtomicInteger();
        OperationResultEncoder<EchoResult> encoder = result -> {
            encodeCalls.incrementAndGet();
            var oversized = JSON.createArrayNode();
            for (int index = 0; index < 100_000; index++) {
                oversized.add(0);
            }
            return oversized;
        };
        OperationDocumentation documentation = documentation(
                "demo.legacy_structure", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.legacy_structure", request -> new EchoResult("ok"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.legacy_structure"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation_output_structure_invalid");
        assertThat(encodeCalls).hasValue(1);
    }

    @Test
    void boundsLegacyNormalizedBytesBeforeCopyingOrRepeating() throws Exception {
        AtomicInteger targetBytes = new AtomicInteger(OperationSafetyLimits.MAX_OUTPUT_BYTES);
        AtomicInteger encodeCalls = new AtomicInteger();
        OperationResultEncoder<EchoResult> encoder = result -> {
            encodeCalls.incrementAndGet();
            try {
                return hardSizedObject(targetBytes.get(), 15);
            } catch (Exception exception) {
                throw new IllegalStateException("test result could not be sized", exception);
            }
        };
        OperationDocumentation documentation = documentation(
                "demo.legacy_byte_limit", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.legacy_byte_limit", request -> new EchoResult("ok"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult exact = directory.execute(
                OperationId.of("demo.legacy_byte_limit"), JSON.readTree("{\"message\":\"ok\"}"));
        targetBytes.set(OperationSafetyLimits.MAX_OUTPUT_BYTES + 1);
        OperationExecutionResult over = directory.execute(
                OperationId.of("demo.legacy_byte_limit"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(exact.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(over.reasonCode()).isEqualTo("operation_output_too_large");
        assertThat(encodeCalls).hasValue(3);
    }

    @Test
    void usesTheBoundedWriteAsTheAuthoritativeResultRepresentation() throws Exception {
        AtomicBoolean encodeCalled = new AtomicBoolean();
        BoundedOperationResultEncoder<EchoResult> encoder = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(EchoResult result) {
                encodeCalled.set(true);
                ObjectNode oversized = JSON.createObjectNode();
                for (int index = 0; index < 60_000; index++) {
                    oversized.put("field" + index, "x");
                }
                return oversized;
            }

            @Override
            public void write(EchoResult result, OutputStream output) throws IOException {
                JSON.writeValue(output, JSON.createObjectNode().put("message", "ok"));
            }
        };
        OperationDocumentation documentation = documentation(
                "demo.authoritative", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.authoritative", request -> new EchoResult("ok"), EchoResult.class,
                documentation, encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.authoritative"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(encodeCalled).isFalse();
    }

    @Test
    void validatesTypedNormalizedStructureBeforeRepeatingTheBoundedEncoding() throws Exception {
        AtomicInteger resultNodes = new AtomicInteger(99_999);
        AtomicInteger writes = new AtomicInteger();
        BoundedOperationResultEncoder<NodeCountResult> encoder = new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(NodeCountResult result) {
                throw new AssertionError("tree encoding must not be used");
            }

            @Override
            public void write(NodeCountResult result, OutputStream output) throws IOException {
                writes.incrementAndGet();
                output.write('[');
                for (int index = 0; index < result.count(); index++) {
                    if (index > 0) {
                        output.write(',');
                    }
                    output.write('0');
                }
                output.write(']');
            }
        };
        OperationDocumentation documentation = documentation(
                "demo.typed_node_limit", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"ok\"}"));
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "typed_node_limit", EchoRequest.class.getName(),
                NodeCountResult.class.getName(), OperationDirectoryTest.class.getName(),
                new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        OperationRegistration<EchoRequest, NodeCountResult> registration = new OperationRegistration<>(
                descriptor, EchoRequest.class, NodeCountResult.class,
                new OperationRegistrationContract(echoSchema(), arrayResultSchema(), documentation.safety()),
                OperationRequestDecoders.typed(JSON, EchoRequest.class),
                request -> new NodeCountResult(resultNodes.get()), encoder);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult exact = directory.execute(
                OperationId.of("demo.typed_node_limit"), JSON.readTree("{\"message\":\"ok\"}"));
        resultNodes.set(100_000);
        OperationExecutionResult over = directory.execute(
                OperationId.of("demo.typed_node_limit"), JSON.readTree("{\"message\":\"ok\"}"));

        assertThat(exact.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(over.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(over.reasonCode()).isEqualTo("operation_output_structure_invalid");
        assertThat(writes).hasValue(3);
    }

    @Test
    void saturatesAndRecoversAfterTimedOutNonCancellableOwnersFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(16);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(16);
        OperationDocumentation documentation = documentation(
                "demo.non_cancellable_saturation", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"wait\"}"), 100, false);
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor =
                boundedNonCancellable(request -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test owner was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test owner was interrupted", exception);
            } finally {
                finished.countDown();
            }
            return new EchoResult("done");
                });
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.non_cancellable_saturation", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));
        ExecutorService callers = Executors.newFixedThreadPool(16);
        List<Future<OperationExecutionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 16; index++) {
                futures.add(callers.submit(() -> directory.execute(
                        OperationId.of("demo.non_cancellable_saturation"),
                        JSON.readTree("{\"message\":\"wait\"}"))));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (Future<OperationExecutionResult> future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS).status())
                        .isEqualTo(OperationExecutionStatus.TIMEOUT);
            }

            OperationExecutionResult saturated = directory.execute(
                    OperationId.of("demo.non_cancellable_saturation"),
                    JSON.readTree("{\"message\":\"wait\"}"));
            assertThat(saturated.reasonCode()).isEqualTo("operation_execution_capacity");

            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(directory.execute(
                OperationId.of("demo.non_cancellable_saturation"),
                JSON.readTree("{\"message\":\"wait\"}")).status())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
    }

    @Test
    void interruptsAnUncooperativeContextAwareOwnerAfterTheCancellationGracePeriod() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        OperationDocumentation documentation = documentation(
                "demo.grace", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"wait\"}"), 100);
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor = (request, context) -> {
            started.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            interrupted.countDown();
            return new EchoResult("interrupted");
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.grace", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        long startNanos = System.nanoTime();
        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.grace"), JSON.readTree("{\"message\":\"wait\"}"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis)
                .isBetween(OperationSafetyLimits.CANCELLATION_GRACE_MILLIS - 100, 3_000L);
    }

    @Test
    void cancelsAContextAwareBoundedDelegateAtTheDeadline() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        OperationDocumentation documentation = documentation(
                "demo.bounded_delegate", "filesystem_write", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"wait\"}"), 100);
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor = ContextAwareOperationExecutor.declared(
                OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                OperationCancellationGuarantee.DELEGATED_DEADLINE, (request, context) -> {
            while (!context.cancellationRequested()) {
                Thread.onSpinWait();
            }
            stopped.set(true);
            return new EchoResult("stopped");
        });
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.bounded_delegate", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.bounded_delegate"),
                JSON.readTree("{\"message\":\"wait\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(stopped).isTrue();
    }

    @Test
    void reportsCooperativeCancellationWithADistinctReasonCode() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.cooperative_cancel", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"cancel\"}"));
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor = (request, context) -> {
            context.requestCancellation();
            return new EchoResult("ignored");
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.cooperative_cancel", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.cooperative_cancel"),
                JSON.readTree("{\"message\":\"cancel\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(result.reasonCode()).isEqualTo("operation_cooperative_cancellation");
    }

    @Test
    void preservesCooperativeCancellationWhenTheOwnerThrows() throws Exception {
        OperationDocumentation documentation = documentation(
                "demo.cooperative_throw", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"cancel\"}"));
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor = (request, context) -> {
            context.requestCancellation();
            throw new CancellationException("cooperative cancellation");
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.cooperative_throw", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.cooperative_throw"),
                JSON.readTree("{\"message\":\"cancel\"}"));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.CANCELLED);
        assertThat(result.reasonCode()).isEqualTo("operation_cooperative_cancellation");
    }

    @Test
    void reportsCallerInterruptionWithADistinctReasonCode() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<OperationExecutionResult> observed = new AtomicReference<>();
        OperationDocumentation documentation = documentation(
                "demo.caller_interrupt", "none", false, null, echoArguments(),
                JSON.readTree("{\"message\":\"interrupt\"}"));
        ContextAwareOperationExecutor<EchoRequest, EchoResult> executor = (request, context) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new EchoResult("done");
        };
        OperationRegistration<EchoRequest, EchoResult> registration = registration(
                "demo.caller_interrupt", executor, EchoResult.class, documentation);
        OperationDirectory directory = new OperationDirectory(
                List.of(registration), document(documentation));
        JsonNode callerInput = JSON.createObjectNode().put("message", "interrupt");
        Thread caller = new Thread(() -> {
            observed.set(directory.execute(
                    OperationId.of("demo.caller_interrupt"),
                    callerInput));
            completed.countDown();
        }, "operation-caller-interruption-test");
        try {
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get().status()).isEqualTo(OperationExecutionStatus.CANCELLED);
            assertThat(observed.get().reasonCode()).isEqualTo("operation_caller_interrupted");
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(2_000);
        }
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

    private OperationRegistration<JsonNode, JsonNode> jsonRegistration(
            String id,
            OperationSafetyPolicy safety,
            OperationExecutor<JsonNode, JsonNode> executor) {
        OperationDescriptor descriptor = new OperationDescriptor(
                id.substring(0, id.indexOf('.')),
                id.substring(id.indexOf('.') + 1),
                JsonNode.class.getName(),
                JsonNode.class.getName(),
                OperationDirectoryTest.class.getName(),
                new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        return OperationRegistration.typed(
                descriptor,
                JsonNode.class,
                JsonNode.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), safety),
                JSON,
                executor::execute);
    }

    private OperationSafetyPolicy hardSafety() {
        return new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none",
                OperationSafetyLimits.MAX_TIMEOUT_MILLIS, true,
                OperationSafetyLimits.MAX_INPUT_BYTES, OperationSafetyLimits.MAX_OUTPUT_BYTES);
    }

    private ObjectNode hardSizedObject(int targetBytes, int fullFieldCount) throws Exception {
        ObjectNode value = JSON.createObjectNode();
        for (int index = 0; index < fullFieldCount; index++) {
            value.put("field" + index, "x".repeat(OperationSafetyLimits.MAX_STRING_VALUE_BYTES));
        }
        String finalField = "field" + fullFieldCount;
        value.put(finalField, "x");
        int currentBytes = JSON.writeValueAsBytes(value).length;
        int finalLength = 1 + targetBytes - currentBytes;
        assertThat(finalLength)
                .isBetween(1, OperationSafetyLimits.MAX_STRING_VALUE_BYTES);
        value.put(finalField, "x".repeat(finalLength));
        assertThat(JSON.writeValueAsBytes(value).length).isEqualTo(targetBytes);
        return value;
    }

    private ObjectNode defaultValueAtDeclarationBytes(int targetBytes) throws Exception {
        ObjectNode value = hardSizedObject(targetBytes - 32, 3);
        ObjectNode declaration = JSON.createObjectNode();
        declaration.set("value", value);
        int difference = targetBytes - JSON.writeValueAsBytes(declaration).length;
        String current = value.path("field3").asText();
        value.put("field3", "x".repeat(current.length() + difference));
        assertThat(JSON.writeValueAsBytes(declaration).length).isEqualTo(targetBytes);
        return value;
    }

    private <O> OperationRegistration<EchoRequest, O> registration(
            String id,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation) {
        return registration(id, input -> JSON.convertValue(input, EchoRequest.class), executor,
                resultType, documentation, OperationResultEncoders.typed(JSON, resultType));
    }

    private <I, O> ContextAwareOperationExecutor<I, O> boundedNonCancellable(
            OperationExecutor<I, O> delegate) {
        return new ContextAwareOperationExecutor<>() {
            @Override
            public O execute(I request, com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext context) {
                return delegate.execute(request);
            }

            @Override
            public OperationCancellationState cancellationState() {
                return OperationCancellationState.NOT_CANCELLABLE;
            }

            @Override
            public OperationCancellationGuarantee cancellationGuarantee() {
                return OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION;
            }
        };
    }

    private <O> OperationRegistration<EchoRequest, O> registration(
            String id,
            OperationRequestDecoder<EchoRequest> decoder,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation) {
        return registration(id, decoder, executor, resultType, documentation,
                OperationResultEncoders.typed(JSON, resultType));
    }

    private <O> OperationRegistration<EchoRequest, O> registration(
            String id,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation,
            OperationResultEncoder<O> encoder) {
        return registration(id, input -> JSON.convertValue(input, EchoRequest.class), executor,
                resultType, documentation, encoder);
    }

    private <O> OperationRegistration<EchoRequest, O> registration(
            String id,
            OperationRequestDecoder<EchoRequest> decoder,
            OperationExecutor<EchoRequest, O> executor,
            Class<O> resultType,
            OperationDocumentation documentation,
            OperationResultEncoder<O> encoder) {
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
                OperationRequestDecoders.wrap(JSON, EchoRequest.class, decoder),
                executor,
                encoder);
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

    private OperationSchema arrayResultSchema() {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "array");
        root.putObject("items").put("type", "integer");
        return new OperationSchema(root);
    }

    private OperationSchema nullFieldSchema(boolean nullable) {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("message").put("type", "string");
        var extra = properties.putObject("extra");
        if (nullable) {
            extra.putArray("oneOf").addObject().put("type", "string");
            extra.withArray("oneOf").addObject().put("type", "null");
        } else {
            extra.put("type", "string");
        }
        root.putArray("required").add("message");
        root.put("additionalProperties", false);
        return new OperationSchema(root);
    }

    private OperationRegistration<NullFieldRequest, EchoResult> nullFieldRegistration(
            String id, OperationSchema schema, OperationDocumentation documentation) {
        OperationDescriptor descriptor = new OperationDescriptor(
                id.substring(0, id.indexOf('.')), id.substring(id.indexOf('.') + 1),
                NullFieldRequest.class.getName(), EchoResult.class.getName(),
                OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        return new OperationRegistration<>(
                descriptor, NullFieldRequest.class, EchoResult.class,
                new OperationRegistrationContract(schema, resultSchema(), documentation.safety()),
                OperationRequestDecoders.wrap(JSON, NullFieldRequest.class,
                        input -> new NullFieldRequest(input.path("message").asText(), null)),
                request -> new EchoResult(request.message()),
                OperationResultEncoders.typed(JSON, EchoResult.class));
    }

    private OperationSchema defaultContainerSchema(boolean declaresItems) {
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        if (declaresItems) {
            root.putObject("properties").putObject("items").put("type", "array")
                    .putObject("items").put("type", "string");
        }
        root.put("additionalProperties", false);
        return new OperationSchema(root);
    }

    private OperationRegistration<DefaultContainerRequest, JsonNode> defaultContainerRegistration(
            String id, OperationSchema schema, OperationDocumentation documentation) {
        OperationDescriptor descriptor = new OperationDescriptor(
                id.substring(0, id.indexOf('.')), id.substring(id.indexOf('.') + 1),
                DefaultContainerRequest.class.getName(), JsonNode.class.getName(),
                OperationDirectoryTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        return new OperationRegistration<>(
                descriptor, DefaultContainerRequest.class, JsonNode.class,
                new OperationRegistrationContract(schema, OperationSchema.empty(), documentation.safety()),
                OperationRequestDecoders.typedWithDefaults(
                        JSON, DefaultContainerRequest.class,
                        Map.of("items", JSON.createArrayNode())),
                ignored -> JSON.createObjectNode(), OperationResultEncoders.typed(JSON, JsonNode.class));
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

    private record NonFiniteResult(double value) {
    }

    private record DefaultContainerRequest(List<String> items) {

        private DefaultContainerRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    private record NodeCountResult(int count) {
    }

    private record NullFieldRequest(String message, String extra) {
    }
}
