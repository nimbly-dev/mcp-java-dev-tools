package com.nimbly.mcpjavadevtools.server.core.feature.probe.registry;

/** Explicit lifecycle port for refreshing the active workspace Probe registry. */
@FunctionalInterface
public interface ProbeRegistryReloader {

    /** Reloads the active registry from its canonical source. */
    ProbeRegistry reload();
}
