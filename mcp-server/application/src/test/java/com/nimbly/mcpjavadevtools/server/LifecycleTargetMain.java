package com.nimbly.mcpjavadevtools.server;

import java.util.concurrent.CountDownLatch;

/**
 * Minimal long-lived Java 21 target used by the executable lifecycle test.
 */
public final class LifecycleTargetMain {

    private LifecycleTargetMain() {
    }

    /** Keeps the target alive until the integration test tears it down. */
    public static void main(String[] args) throws InterruptedException {
        new CountDownLatch(1).await();
    }
}
