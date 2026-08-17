package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint;

import java.time.Duration;

/** Bounded request for the Sidecar failure verification endpoint. */
public record FailureVerifyEvidenceRequest(
        String baseUrl,
        String captureId,
        String expectedExceptionType,
        String expectedRootCauseType,
        String expectedNearestApplicationMethodKey,
        String authorization,
        Duration timeout) {
}
