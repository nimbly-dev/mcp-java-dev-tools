package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded Sidecar HTTP response; raw data is consumed and sanitized by Core. */
public record FailureEvidenceResponse(int status, JsonNode payload) {
}
