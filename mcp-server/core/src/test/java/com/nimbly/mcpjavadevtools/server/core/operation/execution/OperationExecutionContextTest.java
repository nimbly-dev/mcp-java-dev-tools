package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused deadline and normalized-output boundary tests. */
public class OperationExecutionContextTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void comparesDeadlinesCorrectlyAcrossNegativeAndWrappingNanoTimeOrigins() {
        OperationExecutionContext negative = OperationExecutionContext.fromStartNanos(-100_000_000, 100);
        assertThat(negative.remainingNanosAt(-1)).isPositive();
        assertThat(negative.remainingNanosAt(0)).isZero();

        OperationExecutionContext wrapping = OperationExecutionContext.fromStartNanos(
                Long.MAX_VALUE - 50_000_000, 100);
        assertThat(wrapping.deadlineNanos()).isNegative();
        assertThat(wrapping.remainingNanosAt(Long.MAX_VALUE - 1)).isPositive();
        assertThat(wrapping.remainingNanosAt(Long.MIN_VALUE + 49_999_999)).isZero();
    }

    @Test
    void rejectsOversizedAndOverdeepResultsBeforeRedactionCopiesThem() {
        OperationRegistration<Request, ObjectNode> registration = registration();
        ObjectNode oversized = JSON.createObjectNode();
        for (int index = 0; index < 60_000; index++) {
            oversized.put("field" + index, "x".repeat(100));
        }
        OperationExecutionResult byteFailure = OperationInvocationExecution.complete(
                registration, JSON, oversized, OperationExecutionContext.unbounded());
        assertThat(byteFailure.reasonCode()).isEqualTo("operation_output_too_large");

        ObjectNode deep = JSON.createObjectNode();
        ObjectNode current = deep;
        for (int index = 1; index < 65; index++) {
            current = current.putObject("next");
        }
        OperationExecutionResult depthFailure = OperationInvocationExecution.complete(
                registration, JSON, deep, OperationExecutionContext.unbounded());
        assertThat(depthFailure.reasonCode()).isEqualTo("operation_output_structure_invalid");
    }

    @Test
    void enforcesRuntimeEncodedByteLimitsAtTheConfiguredBoundary() throws Exception {
        ObjectNode exact = JSON.createObjectNode().put("value", "exact");
        int exactBytes = JSON.writeValueAsBytes(exact).length;
        OperationExecutionResult accepted = OperationInvocationExecution.complete(
                registration(policy(1_048_576, exactBytes)), JSON, exact,
                OperationExecutionContext.unbounded());
        assertThat(accepted.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);

        ObjectNode oversized = exact.deepCopy().put("value", "exactx");
        assertThat(JSON.writeValueAsBytes(oversized).length).isEqualTo(exactBytes + 1);
        OperationExecutionResult rejected = OperationInvocationExecution.complete(
                registration(policy(1_048_576, exactBytes)), JSON, oversized,
                OperationExecutionContext.unbounded());
        assertThat(rejected.reasonCode()).isEqualTo("operation_output_too_large");

        ObjectNode input = JSON.createObjectNode().put("value", "exact");
        int inputBytes = JSON.writeValueAsBytes(input).length;
        OperationExecutionContext context = OperationExecutionContext.forTimeout(100);
        assertThat(OperationPayloadValidation.input(
                registration(policy(inputBytes, 4_194_304)), JSON, input, context)).isNull();
        ObjectNode inputOver = input.deepCopy().put("value", "exactx");
        assertThat(JSON.writeValueAsBytes(inputOver).length).isEqualTo(inputBytes + 1);
        OperationExecutionResult inputRejected = OperationPayloadValidation.input(
                registration(policy(inputBytes, 4_194_304)), JSON, inputOver, context);
        assertThat(inputRejected.reasonCode()).isEqualTo("operation_input_too_large");
    }

    @Test
    void acceptsTheMaximumTimeoutAndRejectsTheFirstValueAboveIt() {
        assertThat(OperationExecutionContext.forTimeout(OperationSafetyLimits.MAX_TIMEOUT_MILLIS))
                .isNotNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationExecutionContext.forTimeout(
                        OperationSafetyLimits.MAX_TIMEOUT_MILLIS + 1));
    }

    private OperationRegistration<Request, ObjectNode> registration() {
        return registration(OperationSafetyPolicy.legacy("none"));
    }

    private OperationRegistration<Request, ObjectNode> registration(OperationSafetyPolicy policy) {
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "result", Request.class.getName(), ObjectNode.class.getName(),
                OperationExecutionContextTest.class.getName(), new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        return new OperationRegistration<>(
                descriptor, Request.class, ObjectNode.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(),
                        policy),
                input -> new Request(), request -> JSON.createObjectNode(),
                OperationResultEncoders.typed(JSON, ObjectNode.class));
    }

    private OperationSafetyPolicy policy(int maxInputBytes, int maxOutputBytes) {
        return new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "redact_sensitive_fields", 100,
                true, maxInputBytes, maxOutputBytes);
    }

    private record Request() {
    }
}
