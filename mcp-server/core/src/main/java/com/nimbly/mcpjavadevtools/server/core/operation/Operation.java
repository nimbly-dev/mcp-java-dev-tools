package com.nimbly.mcpjavadevtools.server.core.operation;


/**
 * One concrete executable operation registered by a capability-owned catalog.
 *
 * @param <A> closed operation identifier
 * @param <I> typed operation input
 * @param <O> typed operation output
 */
public interface Operation<A extends Enum<A>, I, O> {

    /** @return the one closed identifier owned by this operation */
    A operationId();

    /** @return immutable trace metadata for this executable operation */
    OperationDescriptor descriptor();

    /** @return concrete executable owner identity, independently of the descriptor */
    String executableOwner();

    /**
     * Returns the exact released invocation identity for inventory generation.
     *
     * @return legacy identity associated with this operation
     */
    default OperationLegacyIdentity legacyIdentity() {
        return OperationLegacyIdentity.fromDescriptor(descriptor());
    }

    /**
     * Executes the operation for one typed input.
     *
     * @param input operation input
     * @return operation output
     */
    O execute(I input);
}
