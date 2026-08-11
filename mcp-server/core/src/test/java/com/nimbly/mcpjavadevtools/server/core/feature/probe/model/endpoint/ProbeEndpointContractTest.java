package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProbeEndpointContractTest {

    @Test
    void requestClampsItsTimeoutToTheHardProbeMaximum() {
        ProbeEndpointRequest request = new ProbeEndpointRequest(
                URI.create("http://127.0.0.1:9191/__probe/status"),
                "GET",
                Map.of(),
                "",
                Duration.ofHours(1),
                configuration());

        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void requestRejectsOversizedHeadersAndPayloads() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointRequest(
                URI.create("http://127.0.0.1:9191/__probe/status"),
                "GET",
                headersExceedingLimit(configuration().limits()),
                "",
                Duration.ofSeconds(1),
                configuration()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointRequest(
                URI.create("http://127.0.0.1:9191/__probe/status"),
                "POST",
                Map.of(),
                "a".repeat(configuration().limits().maximumRequestPayloadBytes() + 1),
                Duration.ofSeconds(1),
                configuration()));
    }

    @Test
    void responseRejectsInvalidStatusAndOversizedPayloads() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointResponse(
                99,
                Map.of(),
                "",
                configuration()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "a".repeat(configuration().limits().maximumResponsePayloadBytes() + 1),
                configuration()));
    }

    @Test
    void requestRejectsHeaderInjectionInvalidNamesAndNormalizedDuplicates() {
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "X-Test\r\nInjected", "value")));
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "\r\nX-Test", "value")));
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "X-Test", "value\r\nX-Evil: yes")));
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "X Test", "value")));
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "Authorization", "one",
                "authorization", "two")));
        assertThatIllegalArgumentException().isThrownBy(() -> requestWithHeaders(Map.of(
                "Authorization", "one",
                " Authorization ", "two")));
    }

    @Test
    void endpointLimitsRejectConfigurationsBeyondCoreResourceCeilings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointLimits(65, 128, 4096, 65536, 1048576));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointLimits(64, 129, 4096, 65536, 1048576));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointLimits(64, 128, 4097, 65536, 1048576));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointLimits(64, 128, 4096, 65537, 1048576));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeEndpointLimits(64, 128, 4096, 65536, 1048577));
    }

    @Test
    void endpointPathsRejectUnsafeRelativeReferences() {
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus("//evil.example/probe"));
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus("/__probe/status?override=true"));
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus("/__probe/status#fragment"));
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus("/__probe/../status"));
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus("/__probe/%2e%2e/status"));
        assertThatIllegalArgumentException().isThrownBy(() -> pathsWithStatus(
                "/__probe/" + Character.toString(0) + "status"));
    }

    private ProbeEndpointRequest requestWithHeaders(Map<String, String> headers) {
        return new ProbeEndpointRequest(
                URI.create("http://127.0.0.1:9191/__probe/status"),
                "GET",
                headers,
                "",
                Duration.ofSeconds(1),
                configuration());
    }

    private Map<String, String> headersExceedingLimit(ProbeEndpointLimits limits) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0; index <= limits.maximumHeaderCount(); index++) {
            headers.put("X-Test-" + index, "value");
        }
        return headers;
    }

    private ProbeEndpointPaths pathsWithStatus(String statusPath) {
        return new ProbeEndpointPaths(
                statusPath,
                "/__probe/reset",
                "/__probe/actuate",
                "/__probe/capture",
                "/__probe/profiler");
    }

    private ProbeEndpointConfiguration configuration() {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10);
        ProbeRequestPolicy requestPolicy = new ProbeRequestPolicy(
                Duration.ofSeconds(15),
                Duration.ofMillis(500),
                1,
                false,
                3,
                bounds);
        ProbeEndpointLimits limits = new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576);
        return new ProbeEndpointConfiguration(
                null,
                new ProbeEndpointPaths("/__probe/status", "/__probe/reset", "/__probe/actuate", "/__probe/capture", "/__probe/profiler"),
                requestPolicy,
                limits);
    }
}
