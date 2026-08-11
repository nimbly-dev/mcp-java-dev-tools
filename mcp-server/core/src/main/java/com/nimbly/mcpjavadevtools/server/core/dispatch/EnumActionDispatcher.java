package com.nimbly.mcpjavadevtools.server.core.dispatch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic dispatcher for a complete enum-defined action set.
 *
 * @param <A> closed action discriminator type
 * @param <I> typed dispatcher input
 * @param <O> typed dispatcher output
 */
public final class EnumActionDispatcher<A extends Enum<A>, I, O> {

    private final Map<A, ActionHandler<A, I, O>> handlers;

    /**
     * Creates a dispatcher and verifies that every enum action has exactly one handler.
     *
     * @param actionType closed action enum type
     * @param handlers action handlers
     */
    public EnumActionDispatcher(
            Class<A> actionType,
            List<? extends ActionHandler<A, I, O>> handlers) {
        this.handlers = createCompleteMap(actionType, handlers);
    }

    /**
     * Dispatches one selected action without performing capability-specific work.
     *
     * @param action selected action
     * @param input typed action input
     * @return handler output
     */
    public O dispatch(A action, I input) {
        if (action == null) {
            throw new IllegalArgumentException("unsupported action: null");
        }
        ActionHandler<A, I, O> handler = handlers.get(action);
        if (handler == null) {
            throw new IllegalArgumentException("unsupported action: " + action.name());
        }
        return handler.execute(input);
    }

    private static <A extends Enum<A>, I, O> Map<A, ActionHandler<A, I, O>> createCompleteMap(
            Class<A> actionType,
            List<? extends ActionHandler<A, I, O>> handlers) {
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<A, ActionHandler<A, I, O>> complete = new EnumMap<>(actionType);
        for (ActionHandler<A, I, O> handler : handlers) {
            Objects.requireNonNull(handler, "handlers must not contain null");
            A action = Objects.requireNonNull(handler.action(), "handler action must not be null");
            if (complete.putIfAbsent(action, handler) != null) {
                throw new IllegalArgumentException("duplicate action handler: " + action.name());
            }
        }
        for (A action : actionType.getEnumConstants()) {
            if (!complete.containsKey(action)) {
                throw new IllegalArgumentException("missing action handler: " + action.name());
            }
        }
        return Map.copyOf(complete);
    }
}
