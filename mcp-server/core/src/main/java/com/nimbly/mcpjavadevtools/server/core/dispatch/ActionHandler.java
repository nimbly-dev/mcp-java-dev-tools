package com.nimbly.mcpjavadevtools.server.core.dispatch;

/**
 * Capability-neutral handler for one closed action discriminator.
 *
 * @param <A> closed action discriminator type
 * @param <I> typed dispatcher input
 * @param <O> typed dispatcher output
 */
public interface ActionHandler<A extends Enum<A>, I, O> {

    /**
     * Returns the one action owned by this handler.
     *
     * @return owned action
     */
    A action();

    /**
     * Executes the already-selected action input.
     *
     * @param input typed action input
     * @return typed action output
     */
    O execute(I input);
}
