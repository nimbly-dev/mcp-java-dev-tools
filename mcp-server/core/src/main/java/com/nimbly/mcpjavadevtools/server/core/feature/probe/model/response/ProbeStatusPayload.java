package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response;

/**
 * Typed, compact status payload extracted from an untrusted Sidecar response.
 *
 * @param endpointOk Sidecar batch-row success signal when supplied
 * @param key reported Strict Line Key when supplied
 * @param hitCount reported Probe hit count when supplied
 * @param lastHitEpoch reported last Line Hit epoch when supplied
 * @param lineResolvable line-resolvability signal when supplied
 * @param lineValidation line-validation signal when supplied
 * @param capturePreview compact capture preview when supplied
 * @param runtime safe runtime hints when supplied
 */
public record ProbeStatusPayload(
        Boolean endpointOk,
        String key,
        Long hitCount,
        Long lastHitEpoch,
        Boolean lineResolvable,
        String lineValidation,
        ProbeCapturePreview capturePreview,
        ProbeRuntimeHints runtime) {
}
