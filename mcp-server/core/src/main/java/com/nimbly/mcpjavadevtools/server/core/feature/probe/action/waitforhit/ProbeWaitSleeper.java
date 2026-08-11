package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import java.time.Duration;

/**
 * Intentional interruption-aware collaborator for bounded wait polling.
 */
@FunctionalInterface
public interface ProbeWaitSleeper {

    /**
     * Sleeps for one bounded poll interval.
     *
     * @param duration bounded poll interval
     * @throws InterruptedException when the current wait is interrupted
     */
    void sleep(Duration duration) throws InterruptedException;
}
