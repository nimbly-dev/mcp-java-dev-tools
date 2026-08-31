package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;

/** Explicit typed request/result binding used by the aggregate directory. */
public record OperationRegistration<I, O>(
        OperationDescriptor descriptor,
        Class<I> requestType,
        Class<O> resultType,
        OperationRegistrationContract contract,
        OperationRequestDecoder<I> decoder,
        OperationExecutor<I, O> executor,
        OperationResultEncoder<O> encoder,
        String operationCatalog,
        OperationLegacyIdentity legacyIdentity) {

    /** Creates an explicit registration owned by the aggregate directory. */
    public OperationRegistration(
            OperationDescriptor descriptor,
            Class<I> requestType,
            Class<O> resultType,
            OperationRegistrationContract contract,
            OperationRequestDecoder<I> decoder,
            OperationExecutor<I, O> executor,
            OperationResultEncoder<O> encoder) {
        this(descriptor, requestType, resultType, contract, decoder, executor, encoder,
                OperationRegistration.class.getName(), OperationLegacyIdentity.fromDescriptor(descriptor));
    }

    /** Creates an explicit registration with compatibility and catalog ownership metadata. */
    public OperationRegistration {
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        requestType = Objects.requireNonNull(requestType, "requestType must not be null");
        resultType = Objects.requireNonNull(resultType, "resultType must not be null");
        contract = Objects.requireNonNull(contract, "contract must not be null");
        decoder = Objects.requireNonNull(decoder, "decoder must not be null");
        executor = Objects.requireNonNull(executor, "executor must not be null");
        encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog must not be null");
        if (operationCatalog.isBlank() || operationCatalog.length() > 512) {
            throw new IllegalArgumentException("operationCatalog is outside its bounds");
        }
        legacyIdentity = Objects.requireNonNull(legacyIdentity, "legacyIdentity must not be null");
        if (!requestType.getName().equals(descriptor.requestType())
                || !resultType.getName().equals(descriptor.resultType())) {
            throw new IllegalArgumentException("registration types do not match operation descriptor");
        }
    }

    /** Creates a Jackson-backed binding only when its contract is supplied explicitly. */
    public static <I, O> OperationRegistration<I, O> typed(
            OperationDescriptor descriptor,
            Class<I> requestType,
            Class<O> resultType,
            OperationRegistrationContract contract,
            ObjectMapper mapper,
            Function<I, O> executor) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        return new OperationRegistration<>(
                descriptor,
                requestType,
                resultType,
                contract,
                input -> mapper.convertValue(input, requestType),
                executor::apply,
                result -> mapper.valueToTree(result));
    }

    /** @return Java-owned request schema */
    public OperationSchema inputSchema() {
        return contract.inputSchema();
    }

    /** @return Java-owned result schema */
    public OperationSchema resultSchema() {
        return contract.resultSchema();
    }

    /** @return Java-owned safety policy */
    public OperationSafetyPolicy safety() {
        return contract.safety();
    }

    /** @return immutable legacy-to-CDE compatibility metadata */
    public Map<String, String> compatibility() {
        return legacyIdentity.inventory(descriptor);
    }

    /** Converts, executes, and normalizes one already schema-validated input. */
    public JsonNode execute(JsonNode input) {
        return OperationRegistrationBinding.execute(input, decoder, executor, encoder);
    }

    /** Rebinds only immutable manifest metadata, retaining explicit executable ownership. */
    public OperationRegistration<I, O> withDescriptor(OperationDescriptor replacement) {
        return new OperationRegistration<>(
                replacement,
                requestType,
                resultType,
                contract,
                decoder,
                executor,
                encoder,
                operationCatalog,
                legacyIdentity);
    }
}
