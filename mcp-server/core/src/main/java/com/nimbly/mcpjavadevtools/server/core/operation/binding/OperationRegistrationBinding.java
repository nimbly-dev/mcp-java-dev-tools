package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationBindingException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationNormalizationException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationOutputTooLargeException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationOutputStructureException;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonByteBuffer;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSnapshot;
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
            throw new OperationNormalizationException(
                    "operation result normalization is nondeterministic");
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static <O> JsonNode normalize(
            O result, OperationResultEncoder<O> encoder, int maximumBytes) {
        if (result instanceof JsonNode jsonResult
                && !OperationJsonTreeLimits.violations(jsonResult).isEmpty()) {
            throw new OperationNormalizationException(
                    "operation result contains an invalid JSON number or structure");
        }
        if (!(encoder instanceof BoundedOperationResultEncoder<?> bounded)) {
            return normalizeLegacy(result, encoder, maximumBytes);
        }
        OperationJsonByteBuffer buffer = new OperationJsonByteBuffer(maximumBytes);
        try {
            ((BoundedOperationResultEncoder<O>) bounded).write(result, buffer);
            JsonNode normalized = OperationJsonSnapshot.parse(CANONICAL_MAPPER, buffer.bytes());
            if (normalized == null) {
                throw new OperationNormalizationException("operation returned a null normalized result");
            }
            if (!OperationJsonTreeLimits.violations(normalized).isEmpty()) {
                throw new OperationOutputStructureException(
                        "operation result exceeds its JSON structural limits");
            }
            return normalized;
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                throw new OperationOutputTooLargeException("normalized result exceeds its byte limit");
            }
            throw new OperationNormalizationException("operation result normalization failed", exception);
        } catch (OperationNormalizationException exception) {
            throw exception;
        } catch (OperationOutputTooLargeException exception) {
            throw exception;
        } catch (OperationOutputStructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationNormalizationException("operation result normalization failed", exception);
        } catch (StackOverflowError error) {
            throw new OperationNormalizationException("operation result normalization failed", error);
        }
    }

    static <O> JsonNode normalizeLegacy(
            O result, OperationResultEncoder<O> encoder, int maximumBytes) {
        try {
            JsonNode normalized = encoder.encode(result);
            if (normalized == null) {
                throw new OperationNormalizationException("operation returned a null normalized result");
            }
            if (!OperationJsonTreeLimits.violations(normalized).isEmpty()) {
                throw new OperationOutputStructureException(
                        "operation result exceeds its JSON structural limits");
            }
            if (OperationJsonSize.measure(normalized, maximumBytes) < 0) {
                throw new OperationOutputTooLargeException("normalized result exceeds its byte limit");
            }
            return normalized;
        } catch (OperationNormalizationException exception) {
            throw exception;
        } catch (OperationOutputTooLargeException | OperationOutputStructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationNormalizationException("operation result normalization failed", exception);
        } catch (StackOverflowError error) {
            throw new OperationNormalizationException("operation result normalization failed", error);
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
                throw new OperationBindingException(
                        "operation argument baseline exceeds its byte limit");
            }
            I request = registration.decoder().decode(input);
            if (request == null || !registration.requestType().isInstance(request)) {
                throw new OperationBindingException(
                        "operation argument binding returned an invalid type");
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
                throw new OperationBindingException("operation argument binding is lossy");
            }
            return request;
        } catch (IOException exception) {
            throw new OperationBindingException("operation argument baseline cannot be bounded", exception);
        } catch (OperationBindingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationBindingException("operation argument binding failed", exception);
        } catch (StackOverflowError error) {
            throw new OperationBindingException("operation argument binding failed", error);
        }
    }

    static void validateRebound(
            JsonNode rebound, OperationRegistration<?, ?> registration) {
        if (!OperationJsonTreeLimits.violations(rebound).isEmpty()) {
            throw new OperationBindingException(
                    "operation argument binding exceeds JSON structural limits");
        }
        List<String> schemaViolations = OperationSchemaValidator.violations(
                registration.inputSchema(), rebound);
        if (!schemaViolations.isEmpty()) {
            throw new OperationBindingException("operation argument binding is not schema-valid");
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
                throw new OperationBindingException("operation argument binding returned null JSON");
            }
            return rebound;
        } catch (IOException exception) {
            if (buffer.exceeded()) {
                throw new OperationBindingException(
                        "operation argument binding exceeds its byte limit", exception);
            }
            throw new OperationBindingException("operation argument binding cannot be bounded", exception);
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
