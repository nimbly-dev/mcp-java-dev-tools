package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable complete provider registry for the recognized protocol allowlist. */
public final class TransportProviderRegistry {

    private final Map<TransportProtocol, TransportProvider> providers;

    /**
     * Creates a complete provider registry.
     *
     * @param configuredProviders one provider for every protocol
     */
    public TransportProviderRegistry(List<? extends TransportProvider> configuredProviders) {
        Objects.requireNonNull(configuredProviders, "configuredProviders must not be null");
        Map<TransportProtocol, TransportProvider> values = new EnumMap<>(TransportProtocol.class);
        for (TransportProvider provider : configuredProviders) {
            Objects.requireNonNull(provider, "configuredProviders must not contain null");
            if (values.putIfAbsent(provider.protocol(), provider) != null) {
                throw new IllegalArgumentException("duplicate transport provider: " + provider.protocol());
            }
        }
        for (TransportProtocol protocol : TransportProtocol.values()) {
            if (!values.containsKey(protocol)) {
                throw new IllegalArgumentException("missing transport provider: " + protocol);
            }
        }
        providers = Map.copyOf(values);
    }

    /** @return provider for the recognized protocol */
    public TransportProvider providerFor(TransportProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol must not be null");
        return providers.get(protocol);
    }
}
