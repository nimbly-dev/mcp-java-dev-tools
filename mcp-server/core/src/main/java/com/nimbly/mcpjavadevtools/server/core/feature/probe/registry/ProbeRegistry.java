package com.nimbly.mcpjavadevtools.server.core.feature.probe.registry;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable collection of configured Probe registrations.
 */
public class ProbeRegistry {

    private final Map<String, ProbeRegistration> registrations;

    /**
     * Creates a registry with unique registration identifiers.
     *
     * @param registrations configured registrations
     */
    public ProbeRegistry(Collection<ProbeRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations must not be null");
        Map<String, ProbeRegistration> values = new LinkedHashMap<>();
        for (ProbeRegistration registration : registrations) {
            addRegistration(values, registration);
        }
        this.registrations = Map.copyOf(values);
    }

    /**
     * Finds a registration by its configured identifier.
     *
     * @param probeId configured Probe identifier
     * @return matching registration when present
     */
    public Optional<ProbeRegistration> findById(String probeId) {
        return Optional.ofNullable(registrations.get(probeId));
    }

    /**
     * Returns the only configured Probe identifier when exactly one exists.
     *
     * @return implicit Probe identifier when unambiguous
     */
    public Optional<String> implicitProbeId() {
        if (registrations.size() != 1) {
            return Optional.empty();
        }
        return registrations.keySet().stream().findFirst();
    }

    /**
     * Returns the configured Probe count without exposing registry internals.
     *
     * @return configured Probe count
     */
    public int size() {
        return registrations.size();
    }

    private static void addRegistration(
            Map<String, ProbeRegistration> registrations,
            ProbeRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        ProbeRegistration previous = registrations.putIfAbsent(registration.id(), registration);
        if (previous != null) {
            throw new IllegalArgumentException("Probe registry contains a duplicate id: " + registration.id());
        }
    }
}
