package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooperative cancellation signal shared with one operation owner. */
public class OperationCancellationSignal {

    private final AtomicBoolean requested = new AtomicBoolean();

    /** Requests cancellation; repeated requests are idempotent. */
    public void request() {
        requested.set(true);
    }

    /** @return whether the owning operation should stop cooperatively */
    public boolean requested() {
        return requested.get();
    }
}
