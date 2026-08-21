package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import java.util.Objects;

/** Resolves wrapper policy from the active Probe Registry and fails closed on absence. */
public final class ProbeRegistryTransportExecutionPolicy implements TransportExecutionPolicy {

    private final ProbeRegistryProvider registryProvider;

    /** @param registryProvider active Probe Registry source */
    public ProbeRegistryTransportExecutionPolicy(ProbeRegistryProvider registryProvider) {
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowNonWrappedExecutable() {
        try {
            ProbeRegistry registry = registryProvider.current();
            return registry != null && registry.allowNonWrappedExecutable();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
