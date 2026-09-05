package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable monotonic deadline plus a cooperative cancellation signal for one execution. */
public class OperationExecutionContext {

    private static final ThreadLocal<OperationExecutionContext> CURRENT = new ThreadLocal<>();

    private final long deadlineNanos;
    private final AtomicBoolean cancellationRequested;
    private final boolean unbounded;

    private OperationExecutionContext(
            long deadlineNanos,
            AtomicBoolean cancellationRequested,
            boolean unbounded) {
        if (cancellationRequested == null) {
            throw new IllegalArgumentException("operation execution context is invalid");
        }
        this.deadlineNanos = deadlineNanos;
        this.cancellationRequested = cancellationRequested;
        this.unbounded = unbounded;
    }

    /** Creates a bounded context using the Core timeout policy range. */
    public static OperationExecutionContext forTimeout(long timeoutMillis) {
        if (timeoutMillis < OperationSafetyLimits.MIN_TIMEOUT_MILLIS
                || timeoutMillis > OperationSafetyLimits.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis is outside the supported bounds");
        }
        return fromStartNanos(System.nanoTime(), timeoutMillis);
    }

    /** Creates an unbounded context for direct typed binding calls outside directory execution. */
    public static OperationExecutionContext unbounded() {
        return new OperationExecutionContext(Long.MAX_VALUE, new AtomicBoolean(), true);
    }

    /** @return the context installed for the current operation thread, or an unbounded context */
    public static OperationExecutionContext current() {
        OperationExecutionContext context = CURRENT.get();
        return context == null ? unbounded() : context;
    }

    /** Installs this context and returns a scope that restores the previous one. */
    public OperationExecutionContextScope install() {
        OperationExecutionContext previous = CURRENT.get();
        CURRENT.set(this);
        return new OperationExecutionContextScope(previous);
    }

    /** Requests cooperative cancellation through the shared signal. */
    public void requestCancellation() {
        cancellationRequested.set(true);
    }

    /** @return whether the caller or deadline has requested that execution stop */
    public boolean cancellationRequested() {
        return cancellationRequested.get() || deadlineExpired();
    }

    /** @return whether the monotonic execution deadline has expired */
    public boolean deadlineExpired() {
        return remainingNanosAt(System.nanoTime()) == 0;
    }

    /** @return immutable monotonic deadline in nanoseconds */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /** @return remaining execution time in milliseconds, rounded up and bounded at zero */
    public long remainingMillis() {
        long remaining = unbounded ? Long.MAX_VALUE : remainingNanosAt(System.nanoTime());
        if (remaining == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return remaining == 0 ? 0 : Math.max(1, (remaining + 999_999L) / 1_000_000L);
    }

    static OperationExecutionContext fromStartNanos(long startNanos, long timeoutMillis) {
        if (timeoutMillis < OperationSafetyLimits.MIN_TIMEOUT_MILLIS
                || timeoutMillis > OperationSafetyLimits.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis is outside the supported bounds");
        }
        return new OperationExecutionContext(
                startNanos + timeoutMillis * 1_000_000L,
                new AtomicBoolean(), false);
    }

    long remainingNanosAt(long nowNanos) {
        if (unbounded) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, deadlineNanos - nowNanos);
    }

    static void restore(OperationExecutionContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
