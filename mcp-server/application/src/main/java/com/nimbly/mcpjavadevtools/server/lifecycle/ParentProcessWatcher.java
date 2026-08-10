package com.nimbly.mcpjavadevtools.server.lifecycle;

import java.util.function.LongPredicate;

class ParentProcessWatcher {

    private final long parentProcessId;
    private final LongPredicate parentProcessExists;
    private final Runnable shutdownAction;

    ParentProcessWatcher(long parentProcessId, LongPredicate parentProcessExists, Runnable shutdownAction) {
        this.parentProcessId = parentProcessId;
        this.parentProcessExists = parentProcessExists;
        this.shutdownAction = shutdownAction;
    }

    void checkParent() {
        if (!parentProcessExists.test(parentProcessId)) {
            shutdownAction.run();
        }
    }
}
