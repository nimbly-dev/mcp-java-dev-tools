package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

/**
 * Bounded safe metadata attached to a deterministic Probe outcome.
 *
 * @param failedStep owned processing stage that produced the outcome
 * @param probeId requested or resolved Probe identifier when safe to expose
 * @param probeCount configured Probe count when relevant
 */
public record ProbeReasonMetadata(ProbeFailureStep failedStep, String probeId, Integer probeCount) {

    private static final int MAX_PROBE_ID_LENGTH = 128;
    private static final int MAX_PROBE_COUNT = 10_000;

    /**
     * Bounds optional user-controlled identifiers and registry counts.
     */
    public ProbeReasonMetadata {
        probeId = boundedProbeId(probeId);
        validateProbeCount(probeCount);
    }

    /**
     * Creates empty metadata for outcomes without additional safe context.
     *
     * @return empty metadata
     */
    public static ProbeReasonMetadata empty() {
        return new ProbeReasonMetadata(null, null, null);
    }

    /**
     * Creates routing metadata without exposing endpoint credentials or URLs.
     *
     * @param probeId requested Probe identifier
     * @param probeCount configured Probe count
     * @return routing metadata
     */
    public static ProbeReasonMetadata routing(String probeId, Integer probeCount) {
        return new ProbeReasonMetadata(
                ProbeFailureStep.PROBE_REGISTRY_RESOLUTION,
                probeId,
                safelyRepresentedProbeCount(probeCount));
    }

    /**
     * Creates metadata for structural input validation failures.
     *
     * @return input-validation metadata
     */
    public static ProbeReasonMetadata inputValidation() {
        return new ProbeReasonMetadata(ProbeFailureStep.INPUT_VALIDATION, null, null);
    }

    /**
     * Creates safe metadata for a check diagnostic failure.
     *
     * @return bounded check metadata
     */
    public static ProbeReasonMetadata diagnostics() {
        return new ProbeReasonMetadata(ProbeFailureStep.PROBE_DIAGNOSTICS, null, null);
    }

    /**
     * Creates safe metadata for a status operation failure.
     *
     * @return bounded status metadata
     */
    public static ProbeReasonMetadata status() {
        return new ProbeReasonMetadata(ProbeFailureStep.PROBE_STATUS, null, null);
    }

    /**
     * Creates safe metadata for an untrusted endpoint response.
     *
     * @return bounded endpoint-response metadata
     */
    public static ProbeReasonMetadata endpointResponse() {
        return new ProbeReasonMetadata(ProbeFailureStep.PROBE_ENDPOINT_RESPONSE, null, null);
    }

    /**
     * Creates bounded safe metadata for target-validation failures.
     *
     * @param probeId requested Probe identifier
     * @param probeCount configured Probe count
     * @return target-validation metadata
     */
    public static ProbeReasonMetadata targetValidation(String probeId, Integer probeCount) {
        return new ProbeReasonMetadata(
                ProbeFailureStep.PROBE_TARGET_VALIDATION,
                probeId,
                safelyRepresentedProbeCount(probeCount));
    }

    private static String boundedProbeId(String probeId) {
        if (probeId == null) {
            return null;
        }
        String normalized = probeId.trim();
        if (normalized.length() <= MAX_PROBE_ID_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PROBE_ID_LENGTH);
    }

    private static void validateProbeCount(Integer probeCount) {
        if (probeCount == null) {
            return;
        }
        if (probeCount < 0 || probeCount > MAX_PROBE_COUNT) {
            throw new IllegalArgumentException("probeCount must be between 0 and " + MAX_PROBE_COUNT);
        }
    }

    private static Integer safelyRepresentedProbeCount(Integer probeCount) {
        if (probeCount == null || probeCount <= MAX_PROBE_COUNT) {
            return probeCount;
        }
        return null;
    }
}
