package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint;

import java.time.Duration;

/** Bounded request for the Sidecar failure analyze endpoint. */
public record FailureAnalyzeEvidenceRequest(
        String baseUrl, String trace, String authorization, Duration timeout) {
}
