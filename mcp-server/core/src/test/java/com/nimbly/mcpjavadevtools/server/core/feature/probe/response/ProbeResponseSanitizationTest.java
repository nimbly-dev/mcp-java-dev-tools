package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeResponseSanitizationTest {

    @Test
    void redactsSensitiveHeadersAndRetainsSafeHeaders() {
        Map<String, String> sanitized = ProbeHeaderRedactor.redact(Map.of(
                "Authorization", "Bearer secret-token",
                "X-Credential", "application-secret",
                "X-Request-Id", "request-42"),
                policy(false));

        assertThat(sanitized).containsEntry("Authorization", ProbeHeaderRedactor.REDACTED_VALUE);
        assertThat(sanitized).containsEntry("X-Credential", ProbeHeaderRedactor.REDACTED_VALUE);
        assertThat(sanitized).containsEntry("X-Request-Id", "request-42");
        assertThat(sanitized.values()).doesNotContain("Bearer secret-token");
        assertThat(sanitized.values()).doesNotContain("application-secret");
    }

    @Test
    void captureCompactionExcludesUnsafePayloadFields() {
        Map<String, Object> compacted = ProbeCapturePreviewCompactor.compact(Map.of(
                "captureId", "capture-42",
                "methodKey", "com.example.PostController#updatePost:122",
                "available", true,
                "token", "secret-value",
                "nestedPayload", Map.of("secret", "value"),
                "executionPaths", java.util.List.of("controller", "service")),
                policy(false));

        assertThat(compacted).containsEntry("captureId", "capture-42");
        assertThat(compacted).containsEntry("available", true);
        assertThat(compacted).doesNotContainKeys("token", "nestedPayload", "executionPaths");
    }

    @Test
    void captureCompactionRetainsExecutionPathsOnlyWithAnExplicitOptInPolicy() {
        Map<String, Object> compacted = ProbeCapturePreviewCompactor.compact(Map.of(
                "captureId", "capture-42",
                "executionPaths", java.util.List.of("controller", "service")),
                policy(true));

        assertThat(compacted).containsEntry("executionPaths", java.util.List.of("controller", "service"));
    }

    @Test
    void policyCannotExceedCoreCeilingsOrExpandTheSafeHeaderAllowlist() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeResponseCompactionPolicy(
                false,
                257,
                32,
                64,
                256,
                Set.of("content-type")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeResponseCompactionPolicy(
                false,
                256,
                32,
                64,
                256,
                Set.of("authorization")));
    }

    private ProbeResponseCompactionPolicy policy(boolean includeExecutionPaths) {
        return new ProbeResponseCompactionPolicy(
                includeExecutionPaths,
                256,
                32,
                64,
                256,
                Set.of("content-length", "content-type", "etag", "traceparent", "x-correlation-id", "x-request-id"));
    }
}
