package com.nimbly.mcpjavadevtools.server.core.feature.probe.registry;

/**
 * Supplies the current Probe registry without coupling Core to persistence or Spring.
 */
@FunctionalInterface
public interface ProbeRegistryProvider {

    /**
     * Returns the current registry, or {@code null} when no registry is available.
     *
     * @return current Probe registry
     */
    ProbeRegistry current();
}
