package com.nimbly.mcpjavadevtools.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParentProcessWatcherTest {

    @Test
    void requestsShutdownOnlyWhenTheParentNoLongerExists() {
        AtomicInteger shutdownRequests = new AtomicInteger();
        ParentProcessWatcher activeParent = new ParentProcessWatcher(42, processId -> true, shutdownRequests::incrementAndGet);
        ParentProcessWatcher exitedParent = new ParentProcessWatcher(42, processId -> false, shutdownRequests::incrementAndGet);

        activeParent.checkParent();
        exitedParent.checkParent();

        assertThat(shutdownRequests).hasValue(1);
    }
}
