package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact Catalog-Describe-Execute resolution for one closed capability.
 *
 * @param <A> closed operation identifier
 * @param <I> typed operation input
 * @param <O> typed operation output
 */
public class OperationCatalog<A extends Enum<A>, I, O> {

    private final OperationExposure exposure;
    private final String operationCatalog;
    private final Map<A, Operation<A, I, O>> operations;

    /**
     * Creates a complete catalog and rejects duplicate, missing, or ambiguous
     * operation ownership before the capability can be used.
     *
     * @param operationType closed operation enum
     * @param exposure actual Application MCP registration boundary
     * @param operationCatalog capability-owned catalog/dispatch owner
     * @param registrations production operation registrations
     */
    public OperationCatalog(
            Class<A> operationType,
            OperationExposure exposure,
            Class<?> operationCatalog,
            List<? extends Operation<A, I, O>> registrations) {
        if (operationType == null) {
            throw new IllegalArgumentException("operationType must not be null");
        }
        if (exposure == null) {
            throw new IllegalArgumentException("exposure must not be null");
        }
        if (operationCatalog == null) {
            throw new IllegalArgumentException("operationCatalog must not be null");
        }
        if (registrations == null) {
            throw new IllegalArgumentException("registrations must not be null");
        }
        this.exposure = exposure;
        this.operationCatalog = operationCatalog.getName();
        EnumMap<A, Operation<A, I, O>> registered = new EnumMap<>(operationType);
        Map<String, A> descriptorOwners = new LinkedHashMap<>();
        Set<String> descriptorActions = new HashSet<>();
        for (Operation<A, I, O> operation : registrations) {
            register(operation, registered, descriptorOwners, descriptorActions);
        }
        for (A operationId : operationType.getEnumConstants()) {
            if (!registered.containsKey(operationId)) {
                throw new IllegalArgumentException("missing operation: " + operationId.name());
            }
        }
        if (!new HashSet<>(exposure.actions()).equals(descriptorActions)) {
            throw new IllegalArgumentException("MCP exposure does not match operation catalog");
        }
        EnumMap<A, Operation<A, I, O>> ordered = new EnumMap<>(operationType);
        for (A operationId : operationType.getEnumConstants()) {
            ordered.put(operationId, registered.get(operationId));
        }
        operations = Collections.unmodifiableMap(ordered);
    }

    private void register(
            Operation<A, I, O> operation,
            EnumMap<A, Operation<A, I, O>> registered,
            Map<String, A> descriptorOwners,
            Set<String> descriptorActions) {
        if (operation == null) {
            throw new IllegalArgumentException("registrations must not contain null");
        }
        A operationId = operation.operationId();
        if (operationId == null) {
            throw new IllegalArgumentException("operation identifier must not be null");
        }
        OperationDescriptor descriptor = operation.descriptor();
        if (descriptor == null) {
            throw new IllegalArgumentException("operation descriptor must not be null");
        }
        if (!descriptor.executableOwner().equals(operation.executableOwner())) {
            throw new IllegalArgumentException(
                    "descriptor executable owner does not match operation: " + operationId.name());
        }
        if (registered.putIfAbsent(operationId, operation) != null) {
            throw new IllegalArgumentException("duplicate operation: " + operationId.name());
        }
        if (!descriptor.toolName().equals(exposure.toolName())) {
            throw new IllegalArgumentException("descriptor Tool does not match MCP exposure: " + operationId.name());
        }
        if (!descriptor.trace().mcpAdapter().equals(exposure.adapterType())) {
            throw new IllegalArgumentException(
                    "descriptor adapter does not match MCP exposure: " + operationId.name());
        }
        if (!exposure.actions().contains(descriptor.action())) {
            throw new IllegalArgumentException("descriptor action is not MCP-exposed: " + descriptor.action());
        }
        String descriptorIdentity = descriptor.toolName() + "\u001f" + descriptor.action();
        A previousOwner = descriptorOwners.putIfAbsent(descriptorIdentity, operationId);
        if (previousOwner != null) {
            throw new IllegalArgumentException(
                    "ambiguous operation ownership: " + descriptor.toolName() + "/" + descriptor.action());
        }
        descriptorActions.add(descriptor.action());
    }

    /** @return immutable descriptors in closed-enum declaration order */
    public List<OperationDescriptor> catalog() {
        return operations.values().stream().map(Operation::descriptor).toList();
    }

    /**
     * Describes one operation without executing it.
     *
     * @param operationId selected operation
     * @return stable descriptor
     */
    public OperationDescriptor describe(A operationId) {
        if (operationId == null) {
            throw new IllegalArgumentException("unsupported operation: null");
        }
        Operation<A, I, O> operation = operations.get(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("unsupported operation: " + operationId.name());
        }
        return operation.descriptor();
    }

    /**
     * Executes exactly the registered operation for one identifier.
     *
     * @param operationId selected operation
     * @param input typed operation input
     * @return operation result
     */
    public O execute(A operationId, I input) {
        if (operationId == null) {
            throw new IllegalArgumentException("unsupported operation: null");
        }
        Operation<A, I, O> operation = operations.get(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("unsupported operation: " + operationId.name());
        }
        return operation.execute(input);
    }

    /** @return registered operations for deterministic inventory generation */
    List<Operation<A, I, O>> registeredOperations() {
        return List.copyOf(operations.values());
    }

    OperationExposure exposure() {
        return exposure;
    }

    String operationCatalog() {
        return operationCatalog;
    }

    /** @return generated Tool-to-Operation inventory for this catalog */
    public List<OperationTraceEntry> traceInventory() {
        return OperationTraceInventory.generate(this);
    }
}
