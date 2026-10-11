package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target;

/**
 * Common direct or registry-based Probe target selection input.
 *
 * @param probeId optional registered Probe identifier
 * @param baseUrl optional direct Probe endpoint base URL
 */
public record ProbeTargetSelector(String probeId, String baseUrl) {

    /**
     * Normalizes blank selection values to absent values.
     */
    public ProbeTargetSelector {
        probeId = normalize(probeId);
        baseUrl = normalize(baseUrl);
    }

    /**
     * Returns whether this selector explicitly names a registered Probe.
     *
     * @return whether a Probe identifier is present
     */
    public boolean hasProbeId() {
        return probeId != null;
    }

    /**
     * Returns whether this selector contains a direct base URL.
     *
     * @return whether a direct base URL is present
     */
    public boolean hasBaseUrl() {
        return baseUrl != null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
