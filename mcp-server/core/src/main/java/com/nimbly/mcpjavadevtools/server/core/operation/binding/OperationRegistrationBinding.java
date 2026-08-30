package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionException;

/** Owns the typed boundary call and deterministic result normalization for one registration. */
final class OperationRegistrationBinding {

    private OperationRegistrationBinding() {
    }

    static <I, O> JsonNode execute(
            JsonNode input,
            OperationRequestDecoder<I> decoder,
            OperationExecutor<I, O> executor,
            OperationResultEncoder<O> encoder) {
        try {
            O result = executor.execute(decoder.decode(input));
            JsonNode normalized = encoder.encode(result);
            if (normalized == null) {
                throw new OperationExecutionException("operation returned a null result");
            }
            JsonNode repeated = encoder.encode(result);
            if (repeated == null || !normalized.equals(repeated)) {
                throw new OperationExecutionException(
                        "operation result normalization is nondeterministic");
            }
            return normalized.deepCopy();
        } catch (OperationExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationExecutionException("operation execution failed", exception);
        }
    }
}
