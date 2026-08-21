package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy;

/** Core policy port for the active Probe Registry wrapper decision. */
@FunctionalInterface
public interface TransportExecutionPolicy {

    /** @return true only when the active registry explicitly permits bypass */
    boolean allowNonWrappedExecutable();
}
