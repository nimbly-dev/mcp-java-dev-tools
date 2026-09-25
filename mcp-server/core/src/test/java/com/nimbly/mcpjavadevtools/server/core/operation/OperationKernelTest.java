package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoder;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationFailureException;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationAlias;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationValueRedactor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationJsonTreeLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused requirement tests for the capability-neutral operation kernel. */
public class OperationKernelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void enforcesExactCoreJsonTreeBoundaries() {
        assertThat(OperationJsonTreeLimits.violations(JsonNodeFactory.instance.textNode(
                "a".repeat(OperationSafetyLimits.MAX_STRING_VALUE_BYTES)))).isEmpty();
        assertThat(OperationJsonTreeLimits.violations(JsonNodeFactory.instance.textNode(
                "a".repeat(OperationSafetyLimits.MAX_STRING_VALUE_BYTES + 1)))).isNotEmpty();

        ArrayNode allowed = JSON.createArrayNode();
        for (int index = 0; index < OperationSafetyLimits.MAX_JSON_NODES - 1; index++) {
            allowed.addNull();
        }
        assertThat(OperationJsonTreeLimits.violations(allowed)).isEmpty();
        allowed.addNull();
        assertThat(OperationJsonTreeLimits.violations(allowed)).isNotEmpty();

        JsonNode depthAllowed = nestedObjects(OperationSafetyLimits.MAX_JSON_DEPTH);
        JsonNode depthRejected = nestedObjects(OperationSafetyLimits.MAX_JSON_DEPTH + 1);
        assertThat(OperationJsonTreeLimits.violations(depthAllowed)).isEmpty();
        assertThat(OperationJsonTreeLimits.violations(depthRejected)).isNotEmpty();

        ObjectNode cyclic = JSON.createObjectNode();
        cyclic.set("self", cyclic);
        assertThat(OperationJsonTreeLimits.violations(cyclic))
                .containsExactly("$ contains cyclic JSON");
    }

    @Test
    void checksCancellationWhileScanningWideObjectFields() {
        ObjectNode wide = JSON.createObjectNode();
        for (int index = 0; index < 5_000; index++) {
            wide.put("field" + index, index);
        }
        AtomicInteger checks = new AtomicInteger();

        assertThat(OperationJsonTreeLimits.violations(
                wide, () -> checks.incrementAndGet() > 1_024))
                .containsExactly("$ JSON validation budget expired");
        assertThat(checks.get()).isGreaterThan(1_024);
    }

    @Test
    void enforcesTimeoutAndBytePolicyBoundariesAtRegistrationTime() {
        OperationSafetyPolicy valid = policy(100, 1, 1);
        assertThat(valid.timeoutMillis()).isEqualTo(OperationSafetyLimits.MIN_TIMEOUT_MILLIS);
        assertThat(valid.maxInputBytes()).isEqualTo(1);
        assertThat(valid.maxOutputBytes()).isEqualTo(1);
        assertThat(policy(OperationSafetyLimits.MAX_TIMEOUT_MILLIS, 1, 1).timeoutMillis())
                .isEqualTo(OperationSafetyLimits.MAX_TIMEOUT_MILLIS);
        assertThatIllegalArgumentException().isThrownBy(() -> policy(99, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                OperationSafetyLimits.MAX_TIMEOUT_MILLIS + 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(100, 1_048_577, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(100, 1, 4_194_305));
    }

    @Test
    void rejectsUnsupportedReferenceSiblingsAndBacktrackingPatterns() {
        ObjectNode referenceWithSibling = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("$ref", "#/$defs/value")
                .put("type", "string");
        referenceWithSibling.putObject("$defs").putObject("value").put("type", "string");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(referenceWithSibling))
                .withMessageContaining("$ref siblings");

        ObjectNode unsafePattern = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "string")
                .put("pattern", "(a|aa)+b");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(unsafePattern))
                .withMessageContaining("grouping and alternation");
    }

    @Test
    void failsClosedForTheRawSchemaValidationBoundary() {
        ObjectNode unsupported = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "string")
                .put("x-unsupported", true);

        assertThat(OperationSchemaValidator.violations(unsupported, unsupported,
                JsonNodeFactory.instance.textNode("value")))
                .containsExactly("$ schema is outside the supported subset");
    }

    @Test
    void appliesJsonSchemaNumericUnicodeAndCollectionSemantics() throws Exception {
        OperationSchema integerSchema = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema", "type":"integer"}
                """));
        assertThat(OperationSchemaValidator.violations(integerSchema, JSON.readTree("1.0"))).isEmpty();

        OperationSchema boundedNumber = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"number", "maximum":9007199254740992}
                """));
        assertThat(OperationSchemaValidator.violations(
                boundedNumber, JSON.readTree("9007199254740993"))).isNotEmpty();

        OperationSchema enumSchema = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema", "enum":[1]}
                """));
        assertThat(OperationSchemaValidator.violations(enumSchema, JSON.readTree("1.0"))).isEmpty();

        OperationSchema uniqueSchema = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema", "type":"array",
                 "items":{"type":"number"}, "uniqueItems":true}
                """));
        assertThat(OperationSchemaValidator.violations(uniqueSchema, JSON.readTree("[1,1.0]")))
                .isNotEmpty();

        OperationSchema unicodeSchema = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"string", "maxLength":1}
                """));
        assertThat(OperationSchemaValidator.violations(unicodeSchema, JSON.readTree("\"😀\"")))
                .isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(JSON.readTree("""
                        {"$schema":"https://json-schema.org/draft/2020-12/schema",
                         "minimum":9007199254740993, "maximum":9007199254740992}
                        """)))
                .withMessageContaining("numeric bounds are inverted");
        ObjectNode nonFinite = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "number")
                .put("minimum", Double.NaN);
        JsonNode nan = DoubleNode.valueOf(Double.NaN);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(nonFinite))
                .withMessageContaining("numeric bound is invalid");

        ObjectNode nonFiniteEnum = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "number");
        nonFiniteEnum.putArray("enum").add(nan);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationSchema(nonFiniteEnum))
                .withMessageContaining("invalid JSON value");

        assertThat(OperationJsonTreeLimits.violations(nan))
                .containsExactly("$ contains a non-finite numeric value");
        assertThat(OperationSchemaValidator.violations(OperationSchema.empty(), nan))
                .containsExactly("$ contains a non-finite numeric value");
    }

    @Test
    void appliesObjectAndArrayKeywordsWhenTheSchemaOmitsType() throws Exception {
        ObjectNode objectDefinition = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("additionalProperties", false);
        objectDefinition.putObject("properties").putObject("value").put("type", "string");
        objectDefinition.putArray("required").add("value");
        OperationSchema objectSchema = new OperationSchema(objectDefinition);

        assertThat(OperationSchemaValidator.violations(objectSchema, JSON.createObjectNode()))
                .contains("$.value is required");
        assertThat(OperationSchemaValidator.violations(
                objectSchema, JSON.readTree("{\"value\":\"ok\",\"extra\":true}")))
                .contains("$.extra is not supported");

        ObjectNode arrayDefinition = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema");
        arrayDefinition.putObject("items").put("type", "integer");
        OperationSchema arraySchema = new OperationSchema(arrayDefinition);
        assertThat(OperationSchemaValidator.violations(arraySchema, JSON.readTree("[\"not-integer\"]")))
                .contains("$[0] must match the declared type");
    }

    @Test
    void stopsResultRedactionWhenItsCooperativeBudgetExpires() {
        ObjectNode result = JSON.createObjectNode();
        for (int index = 0; index < 256; index++) {
            result.put("field" + index, index);
        }
        AtomicInteger checks = new AtomicInteger();

        assertThat(OperationValueRedactor.redact(
                result, "redact_sensitive_fields",
                () -> checks.incrementAndGet() > 8)).isNull();
    }

    @Test
    void stopsEnumAndUniqueItemScansWhenTheValidationBudgetExpires() throws Exception {
        ObjectNode enumDefinition = JSON.createObjectNode()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "integer");
        ArrayNode enumValues = enumDefinition.putArray("enum");
        for (int value = 0; value < 32; value++) {
            enumValues.add(value);
        }
        OperationSchema enumSchema = new OperationSchema(enumDefinition);
        AtomicInteger enumChecks = new AtomicInteger();
        assertThat(OperationSchemaValidator.violations(
                enumSchema, JSON.readTree("999"), () -> enumChecks.incrementAndGet() > 5))
                .contains("$ JSON validation budget expired");

        OperationSchema uniqueSchema = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema", "type":"array",
                 "items":{"type":"integer"}, "uniqueItems":true}
                """));
        ArrayNode values = JSON.createArrayNode();
        for (int value = 0; value < 256; value++) {
            values.add(value);
        }
        AtomicInteger uniqueChecks = new AtomicInteger();
        assertThat(OperationSchemaValidator.violations(
                uniqueSchema, values, () -> uniqueChecks.incrementAndGet() > 265))
                .contains("$ JSON validation budget expired");
    }

    @Test
    void normalizesBinderAndNormalizerFailuresToStableReasons() {
        OperationDirectory bindingFailure = directory(
                input -> { throw new IllegalStateException("hidden"); },
                request -> new Result("unused"),
                OperationSchema.empty(), OperationSchema.empty(), policy(100, 1_048_576, 4_194_304));
        OperationExecutionResult binding = bindingFailure.execute(
                OperationId.of("demo.echo"), JSON.createObjectNode());
        assertThat(binding.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(binding.reasonCode()).isEqualTo("operation_binding_failed");

        AtomicBoolean first = new AtomicBoolean(true);
        OperationDirectory normalizerFailure = directory(
                input -> new Request(),
                request -> new Result("ok"),
                OperationSchema.empty(), OperationSchema.empty(), policy(100, 1_048_576, 4_194_304),
                boundedEncoder(result -> JsonNodeFactory.instance.textNode(
                        first.getAndSet(false) ? "one" : "two")));
        OperationExecutionResult normalized = normalizerFailure.execute(
                OperationId.of("demo.echo"), JSON.createObjectNode());
        assertThat(normalized.status()).isEqualTo(OperationExecutionStatus.FAILED);
        assertThat(normalized.reasonCode()).isEqualTo("operation_normalization_failed");
    }

    @Test
    void preservesOutputFailureReasonsThroughTheTypedBindingBoundary() throws Exception {
        OperationDirectory oversized = directory(
                input -> new Request(), request -> new Result("oversized"),
                OperationSchema.empty(), OperationSchema.empty(), policy(100, 1_048_576, 10),
                boundedEncoder(result -> JSON.createObjectNode().put("value", result.value())));
        assertThat(oversized.execute(OperationId.of("demo.echo"), JSON.createObjectNode())
                .reasonCode()).isEqualTo("operation_output_too_large");

        OperationDirectory overdeep = directory(
                input -> new Request(), request -> new Result("overdeep"),
                OperationSchema.empty(), OperationSchema.empty(), policy(100, 1_048_576, 4_194_304),
                boundedEncoder(result -> nestedObjects(OperationSafetyLimits.MAX_JSON_DEPTH + 1)));
        assertThat(overdeep.execute(OperationId.of("demo.echo"), JSON.createObjectNode())
                .reasonCode()).isEqualTo("operation_output_structure_invalid");

        OperationSchema invalidResult = new OperationSchema(JSON.readTree("""
                {"$schema":"https://json-schema.org/draft/2020-12/schema", "type":"integer"}
                """));
        OperationDirectory schemaInvalid = directory(
                input -> new Request(), request -> new Result("schema-invalid"),
                OperationSchema.empty(), invalidResult, policy(100, 1_048_576, 4_194_304),
                boundedEncoder(result -> JsonNodeFactory.instance.textNode(result.value())));
        assertThat(schemaInvalid.execute(OperationId.of("demo.echo"), JSON.createObjectNode())
                .reasonCode()).isEqualTo("operation_result_schema_invalid");
    }

    @Test
    void exposesLegacyAndContextAwareCancellationStatesInTraceEvidence() {
        OperationDirectory legacy = directory(
                input -> new Request(), request -> new Result("ok"),
                OperationSchema.empty(), OperationSchema.empty(), policy(100, 1_048_576, 4_194_304));
        assertThat(legacy.manifest().traceInventory().getFirst().compatibility())
                .containsEntry("cancellationState", "LEGACY_UNVERIFIED_CANCELLATION");

        ContextAwareOperationExecutor<Request, Result> contextAware =
                (request, context) -> new Result(context.cancellationRequested() ? "cancelled" : "ok");
        OperationDirectory contextDirectory = directory(
                input -> new Request(), contextAware, OperationSchema.empty(), OperationSchema.empty(),
                policy(100, 1_048_576, 4_194_304));
        assertThat(contextDirectory.manifest().traceInventory().getFirst().compatibility())
                .containsEntry("cancellationState", "CONTEXT_AWARE_CANCELLATION");

        ContextAwareOperationExecutor<Request, Result> bounded = ContextAwareOperationExecutor.declared(
                OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION,
                OperationCancellationGuarantee.DELEGATED_DEADLINE, (request, context) -> new Result("bounded"));
        assertThat(OperationCancellationSupport.state(bounded, policy(
                100, 1_048_576, 4_194_304)))
                .isEqualTo(OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION);
        assertThat(OperationCancellationSupport.state(
                (OperationExecutor<Request, Result>) request -> new Result("none"),
                new OperationSafetyPolicy(
                        "none", false, "caller_must_not_supply_credentials", "none", 100, false,
                        1_048_576, 4_194_304)))
                .isEqualTo(OperationCancellationState.NOT_CANCELLABLE);
    }

    @Test
    void passesTheMonotonicDeadlineAndCancellationSignalToContextAwareOwners() {
        AtomicBoolean observed = new AtomicBoolean();
        ContextAwareOperationExecutor<Request, Result> executor = (request, context) -> {
            observed.set(context.deadlineNanos() > System.nanoTime());
            while (!context.cancellationRequested()) {
                Thread.onSpinWait();
            }
            return new Result("stopped");
        };
        OperationDirectory directory = directory(
                input -> new Request(), executor, OperationSchema.empty(), OperationSchema.empty(),
                policy(100, 1_048_576, 4_194_304));

        OperationExecutionResult result = directory.execute(
                OperationId.of("demo.echo"), JSON.createObjectNode());

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.TIMEOUT);
        assertThat(result.reasonCode()).isEqualTo("operation_timeout");
        assertThat(observed).isTrue();
    }

    @Test
    void rejectsSeventeenthConcurrentExecutionAndRecoversCapacity() throws Exception {
        CountDownLatch executionsStarted = new CountDownLatch(
                OperationSafetyLimits.MAX_CONCURRENT_EXECUTIONS);
        CountDownLatch release = new CountDownLatch(1);
        OperationExecutor<Request, Result> blocking = request -> {
            executionsStarted.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test execution was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test execution was interrupted", exception);
            }
            return new Result("ok");
        };
        OperationDirectory directory = directory(
                input -> new Request(), blocking, OperationSchema.empty(), OperationSchema.empty(),
                policy(300_000, 1_048_576, 4_194_304));
        ExecutorService callers = Executors.newFixedThreadPool(
                OperationSafetyLimits.MAX_CONCURRENT_EXECUTIONS);
        List<Future<OperationExecutionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < OperationSafetyLimits.MAX_CONCURRENT_EXECUTIONS; index++) {
                futures.add(callers.submit(() -> directory.execute(
                        OperationId.of("demo.echo"), JSON.createObjectNode())));
            }
            assertThat(executionsStarted.await(2, TimeUnit.SECONDS)).isTrue();
            OperationExecutionResult saturated = directory.execute(
                    OperationId.of("demo.echo"), JSON.createObjectNode());
            assertThat(saturated.reasonCode()).isEqualTo("operation_execution_capacity");
            release.countDown();

            List<OperationExecutionResult> results = new ArrayList<>();
            for (Future<OperationExecutionResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(results.stream()
                    .filter(result -> result.status() == OperationExecutionStatus.SUCCEEDED)
                    .count()).isEqualTo(OperationSafetyLimits.MAX_CONCURRENT_EXECUTIONS);
        } finally {
            release.countDown();
            callers.shutdownNow();
            callers.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(directory.execute(OperationId.of("demo.echo"), JSON.createObjectNode()).status())
                .isEqualTo(OperationExecutionStatus.SUCCEEDED);
    }

    private JsonNode nestedObjects(int depth) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode current = root;
        for (int index = 1; index < depth; index++) {
            current = current.putObject("next");
        }
        return root;
    }

    private OperationSafetyPolicy policy(long timeout, int inputBytes, int outputBytes) {
        return new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", timeout, true,
                inputBytes, outputBytes);
    }

    private OperationDirectory directory(
            OperationRequestDecoder<Request> decoder,
            OperationExecutor<Request, Result> executor,
            OperationSchema inputSchema,
            OperationSchema resultSchema,
            OperationSafetyPolicy policy) {
        return directory(decoder, executor, inputSchema, resultSchema, policy,
                OperationResultEncoders.typed(JSON, Result.class));
    }

    @Test
    void rejectsUnrecognizedInternalFailureReasonCodes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationFailureException(
                        "safe failure", "token=REVIEW_SENTINEL"))
                .withMessage("operation failure reason code is not recognized");
    }

    private OperationDirectory directory(
            OperationRequestDecoder<Request> decoder,
            OperationExecutor<Request, Result> executor,
            OperationSchema inputSchema,
            OperationSchema resultSchema,
            OperationSafetyPolicy policy,
            BoundedOperationResultEncoder<Result> encoder) {
        String id = "demo.echo";
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "echo", Request.class.getName(), Result.class.getName(),
                OperationKernelTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "kernel-test", "none",
                        Map.of("owner", OperationKernelTest.class.getName())));
        OperationRegistration<Request, Result> registration = new OperationRegistration<>(
                descriptor, Request.class, Result.class,
                new OperationRegistrationContract(inputSchema, resultSchema, policy),
                OperationRequestDecoders.wrap(JSON, Request.class, decoder), executor, encoder);
        OperationDocumentation documentation = new OperationDocumentation(
                "Echo", "Echoes a request.", "demo", List.of(),
                List.of(JSON.createObjectNode()), List.of("kernel"),
                List.of(new OperationAlias("demo", "echo")), policy);
        return new OperationDirectory(
                List.of(registration), new com.nimbly.mcpjavadevtools.server.core.operation.manifest
                        .OperationManifestDocument(1, Map.of(OperationId.of(id), documentation)));
    }

    private BoundedOperationResultEncoder<Result> boundedEncoder(
            java.util.function.Function<Result, JsonNode> normalizer) {
        return new BoundedOperationResultEncoder<>() {
            @Override
            public JsonNode encode(Result result) {
                return normalizer.apply(result);
            }

            @Override
            public void write(Result result, OutputStream output) throws IOException {
                JSON.writeValue(output, normalizer.apply(result));
            }
        };
    }

    private record Request() {
    }

    private record Result(String value) {
    }
}
