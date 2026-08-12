package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allows loopback and explicitly configured local Probe hosts.
 */
public final class ProbeHostPolicy {

    private static final String LOOPBACK_IPV4 = String.join(".", "127", "0", "0", "1");
    private static final String LOOPBACK_IPV6 = String.join(":", "", "", "1");
    private final Set<String> configuredHosts;

    /** Creates a normalized allowlist. */
    public ProbeHostPolicy(Collection<String> configuredHosts) {
        configuredHosts = configuredHosts == null ? Set.of() : configuredHosts;
        this.configuredHosts = configuredHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns whether the host is loopback or explicitly allowlisted. */
    public boolean isAllowed(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals(LOOPBACK_IPV4)
                || normalized.equals("localhost")
                || normalized.equals(LOOPBACK_IPV6)
                || configuredHosts.contains(normalized);
    }
}
