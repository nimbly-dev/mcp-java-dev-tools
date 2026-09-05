package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationFailureException;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSnapshot;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonByteBuffer;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSize;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationJsonTreeLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Owns the typed boundary call and deterministic result normalization for one registration. */
public class OperationRegistrationBinding {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();
    private static final String BINDING_FAILURE = "operation_binding_failed";
    private static final String NORMALIZATION_FAILURE = "operation_normalization_failed";
    private static final String NORMALIZATION_FAILED = "operation result normalization failed";
    private static final String NULL_NORMALIZED_RESULT = "operation returned a null normalized result";
    private static final String OUTPUT_STRUCTURE_INVALID =
            "operation result exceeds its JSON structural limits";

    private OperationRegistrationBinding() {
    }

    static <I, O> JsonNode execute(
            JsonNode input,
            OperationRegistration<I, O> registration) {
        I request = bind(input, registration);
        O result = executeOwner(request, registration);
        JsonNode normalized = normalize(
                result, registration.encoder(), registration.safety().maxOutputBytes());
        JsonNode snapshot = normalized.deepCopy();
        JsonNode repeated = normalize(
                result, registration.encoder(), registration.safety().maxOutputBytes());
        if (!snapshot.equals(repeated)) {
            throw new OperationFailureException(
                    "operation result normalization is nondeterministic",
                    "operation_normalization_failed");
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static <O> JsonNode normalize(
            O result, OperationResultEncoder<O> encoder, int maximumBytes) {
        if (result instanceof JsonNode jsonResult
                && !OperationJsonTreeLimits.violations(jsonResult).isEmpty()) {
            throw new OperationFailureException(
                    "operation result contains an invalid JSON number or structure",
                    "operation_normalization_failed");
        }
        if (!(encoder instanceof BoundedOperationResultEncoder<?> bounded)) {
            return normalizeLegacy(result, encoder, maximumBytes);
        }
        OperationJsonByteBuffer buffer = new OperationJsonByteBuffer(maximumBytes);
        try {
            ((BoundedOperationResultEncoder<O>) bounded).write(result, buffer);
            JsonNode normalized = OperationJsonSnapshot.parse(CANONICAL_MAPPER, buffer.bytes());
            if (normalized == null) {
                throw new OperationFailureException(NULL_NORMALIZED_RESULT, NORMALIZATION_FAILURE);
            }
            if (!OperationJsonTreeLimits.violations(normalized).isEmpty()) {
                throw new OperationFailureException(
                        OUTPUT_STRUCTURE_INVALID, "operation_output_structure_invalid");
            }
            return normalized;
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                throw new OperationFailureException(
                        "normalized result exceeds its byte limit",
                        "operation_output_too_large");
            }
            throw new OperationFailureException(
                    NORMALIZATION_FAILED, NORMALIZATION_FAILURE, exception);
        } catch (OperationFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationFailureException(
                    NORMALIZATION_FAILED, NORMALIZATION_FAILURE, exception);
        } catch (StackOverflowError error) {
            throw new OperationFailureException(NORMALIZATION_FAILED, NORMALIZATION_FAILURE, error);
        }
    }

    static <O> JsonNode normalizeLegacy(
            O result, OperationResultEncoder<O> encoder, int maximumBytes) {
        try {
            JsonNode normalized = encoder.encode(result);
            if (normalized == null) {
                throw new OperationFailureException(NULL_NORMALIZED_RESULT, NORMALIZATION_FAILURE);
            }
            if (!OperationJsonTreeLimits.violations(normalized).isEmpty()) {
                throw new OperationFailureException(
                        OUTPUT_STRUCTURE_INVALID, "operation_output_structure_invalid");
            }
            if (OperationJsonSize.measure(normalized, maximumBytes) < 0) {
                throw new OperationFailureException(
                        "normalized result exceeds its byte limit",
                        "operation_output_too_large");
            }
            return normalized;
        } catch (OperationFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationFailureException(
                    NORMALIZATION_FAILED, NORMALIZATION_FAILURE, exception);
        } catch (StackOverflowError error) {
            throw new OperationFailureException(NORMALIZATION_FAILED, NORMALIZATION_FAILURE, error);
        }
    }

    static <I, O> I bind(JsonNode input, OperationRegistration<I, O> registration) {
        try {
            BoundedOperationRequestDecoder<?> bounded = null;
            if (registration.decoder() instanceof BoundedOperationRequestDecoder<?> value) {
                bounded = value;
            }
            JsonNode baseline = bounded == null ? null : OperationJsonSnapshot.copy(
                    CANONICAL_MAPPER, input, registration.safety().maxInputBytes());
            if (bounded != null && baseline == null) {
                throw new OperationFailureException(
                        "operation argument baseline exceeds its byte limit", BINDING_FAILURE);
            }
            I request = registration.decoder().decode(input);
            if (request == null || !registration.requestType().isInstance(request)) {
                throw new OperationFailureException(
                        "operation argument binding returned an invalid type", BINDING_FAILURE);
            }
            if (bounded == null) {
                return request;
            }
            JsonNode rebound = rebind(request, bounded, registration.safety().maxInputBytes());
            validateRebound(rebound, registration);
            Map<String, JsonNode> defaultFields = bounded instanceof OperationRequestDecoderAdapter<?> adapter
                    ? adapter.defaultFields() : Map.of();
            if (!OperationRequestEquivalence.equivalent(
                    baseline, rebound, defaultFields, registration.inputSchema())) {
                throw new OperationFailureException(
                        "operation argument binding is lossy", BINDING_FAILURE);
            }
            return request;
        } catch (IOException exception) {
            throw new OperationFailureException(
                    "operation argument baseline cannot be bounded",
                    BINDING_FAILURE,
                    exception);
        } catch (OperationFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationFailureException(
                    "operation argument binding failed", BINDING_FAILURE, exception);
        } catch (StackOverflowError error) {
            throw new OperationFailureException(
                    "operation argument binding failed", BINDING_FAILURE, error);
        }
    }

    static void validateRebound(
            JsonNode rebound, OperationRegistration<?, ?> registration) {
        if (!OperationJsonTreeLimits.violations(rebound).isEmpty()) {
            throw new OperationFailureException(
                    "operation argument binding exceeds JSON structural limits",
                    "operation_binding_failed");
        }
        List<String> schemaViolations = OperationSchemaValidator.violations(
                registration.inputSchema(), rebound);
        if (!schemaViolations.isEmpty()) {
            throw new OperationFailureException(
                    "operation argument binding is not schema-valid",
                    "operation_binding_failed");
        }
    }

    @SuppressWarnings("unchecked")
    static <I> JsonNode rebind(
            I request, BoundedOperationRequestDecoder<?> decoder, int maximumBytes) {
        OperationJsonByteBuffer buffer = new OperationJsonByteBuffer(maximumBytes);
        try {
            ((BoundedOperationRequestDecoder<I>) decoder).write(request, buffer);
            JsonNode rebound = OperationJsonSnapshot.parse(CANONICAL_MAPPER, buffer.bytes());
            if (rebound == null) {
                throw new OperationFailureException(
                        "operation argument binding returned null JSON",
                        "operation_binding_failed");
            }
            return rebound;
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                throw new OperationFailureException(
                        "operation argument binding exceeds its byte limit",
                        "operation_binding_failed",
                        exception);
            }
            throw new OperationFailureException(
                    "operation argument binding cannot be bounded",
                    "operation_binding_failed",
                    exception);
        }
    }

    static <I, O> O executeOwner(I request, OperationRegistration<I, O> registration) {
        try {
            O result = registration.executor().execute(request);
            if (result == null || !registration.resultType().isInstance(result)) {
                throw new OperationExecutionException("operation returned an invalid result type");
            }
            return result;
        } catch (OperationExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationExecutionException("operation execution failed", exception);
        }
    }

}
