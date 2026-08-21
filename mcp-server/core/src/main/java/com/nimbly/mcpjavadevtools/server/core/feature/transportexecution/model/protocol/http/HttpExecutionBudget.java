package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http;

import java.util.concurrent.TimeUnit;

/** One operation-wide HTTP timeout budget shared by every redirect hop. */
public record HttpExecutionBudget(long startedNanos, long deadlineNanos) {

    /** Creates a budget whose deadline is measured from the complete operation start. */
    public static HttpExecutionBudget startingAt(long startedNanos, int timeoutMillis) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return new HttpExecutionBudget(startedNanos, startedNanos + timeoutNanos);
    }

    /** Returns the positive time remaining, or zero when the operation expired. */
    public long remainingNanos(long currentNanos) {
        return Math.max(0, deadlineNanos - currentNanos);
    }

    /** Returns the stable minimum observable elapsed duration. */
    public long elapsedMillis(long currentNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(currentNanos - startedNanos));
    }
}
