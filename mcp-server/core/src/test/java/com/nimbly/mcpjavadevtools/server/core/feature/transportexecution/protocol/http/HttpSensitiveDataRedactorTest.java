package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpSensitiveDataRedactorTest {

    private final HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();

    @Test
    void redactsCredentialsFromEmbeddedJson() {
        String preview = redactor.redactPreview(
                "response-prefix {\"token\":\"secret-value\",\"safe\":\"ok\"} response-suffix");

        assertThat(preview).contains("response-prefix", "[REDACTED]", "response-suffix")
                .doesNotContain("secret-value");
    }

    @Test
    void redactsCredentialsFromMalformedJson() {
        String preview = redactor.redactPreview(
                "{\"safe\":\"ok\",\"apiKey\":\"secret-value");

        assertThat(preview).contains("[REDACTED]").doesNotContain("secret-value");
    }
}
