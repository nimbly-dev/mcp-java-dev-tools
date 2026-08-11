package com.nimbly.mcpjavadevtools.server.core.feature.probe.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSource;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProbeTargetResolverTest {

    @Test
    void probeIdTakesPrecedenceOverDirectBaseUrl() {
        ProbeTargetResolver resolver = resolver(
                null,
                new ProbeRegistration("orders", "http://127.0.0.1:9191"));

        ProbeTargetResolution resolution = resolver.resolve(
                new ProbeTargetSelector("orders", "http://127.0.0.1:9292"));

        assertThat(resolution).isInstanceOf(ResolvedProbeTarget.class);
        ResolvedProbeTarget resolved = (ResolvedProbeTarget) resolution;
        assertThat(resolved.target().baseUrl().toString()).isEqualTo("http://127.0.0.1:9191");
        assertThat(resolved.target().source()).isEqualTo(ProbeTargetSource.PROBE_ID);
    }

    @Test
    void missingRegisteredProbeReturnsTheExistingDeterministicReasonCode() {
        ProbeTargetResolver resolver = resolver(
                null,
                new ProbeRegistration("orders", "http://127.0.0.1:9191"));

        ProbeTargetResolution resolution = resolver.resolve(new ProbeTargetSelector("missing", null));

        assertThat(resolution).isInstanceOf(UnresolvedProbeTarget.class);
        UnresolvedProbeTarget unresolved = (UnresolvedProbeTarget) resolution;
        assertThat(unresolved.result().status()).isEqualTo(ProbeResultStatus.BLOCKED);
        assertThat(unresolved.result().reasonCode()).isEqualTo(ProbeReasonCode.PROBE_ID_UNKNOWN);
        assertThat(unresolved.result().reasonMetadata().probeId()).isEqualTo("missing");
    }

    @Test
    void ambiguousRegistryWithoutAConfiguredDefaultFailsClosed() {
        ProbeTargetResolver resolver = resolver(
                null,
                new ProbeRegistration("orders", "http://127.0.0.1:9191"),
                new ProbeRegistration("payments", "http://127.0.0.1:9292"));

        ProbeTargetResolution resolution = resolver.resolve(new ProbeTargetSelector(null, null));

        assertThat(resolution).isInstanceOf(UnresolvedProbeTarget.class);
        UnresolvedProbeTarget unresolved = (UnresolvedProbeTarget) resolution;
        assertThat(unresolved.result().status()).isEqualTo(ProbeResultStatus.BLOCKED);
        assertThat(unresolved.result().reasonCode()).isEqualTo(ProbeReasonCode.PROBE_ID_REQUIRED);
        assertThat(unresolved.result().reasonMetadata().probeCount()).isEqualTo(2);
    }

    @Test
    void oversizedAmbiguousRegistryReturnsTheDeterministicMissingTargetOutcome() {
        List<ProbeRegistration> registrations = IntStream.range(0, 10_001)
                .mapToObj(index -> new ProbeRegistration(
                        "probe-" + index,
                        "http://127.0.0.1:9191"))
                .toList();
        ProbeTargetResolver resolver = new ProbeTargetResolver(
                endpointConfiguration(null),
                new ProbeRegistry(registrations));

        ProbeTargetResolution resolution = resolver.resolve(new ProbeTargetSelector(null, null));

        assertThat(resolution).isInstanceOf(UnresolvedProbeTarget.class);
        UnresolvedProbeTarget unresolved = (UnresolvedProbeTarget) resolution;
        assertThat(unresolved.result().reasonCode()).isEqualTo(ProbeReasonCode.PROBE_ID_REQUIRED);
        assertThat(unresolved.result().reasonMetadata().probeCount()).isNull();
    }

    @Test
    void directBaseUrlResolvesWithoutARegistry() {
        ProbeTargetResolver resolver = resolver(null);

        ProbeTargetResolution resolution = resolver.resolve(
                new ProbeTargetSelector(null, "https://probe.example.test:9191/"));

        assertThat(resolution).isInstanceOf(ResolvedProbeTarget.class);
        ResolvedProbeTarget resolved = (ResolvedProbeTarget) resolution;
        assertThat(resolved.target().probeId()).isNull();
        assertThat(resolved.target().source()).isEqualTo(ProbeTargetSource.DIRECT_BASE_URL);
    }

    @Test
    void invalidBaseUrlReturnsADeterministicFailureWithoutLeakingParserDetail() {
        ProbeTargetResolver resolver = resolver(null);

        ProbeTargetResolution resolution = resolver.resolve(new ProbeTargetSelector(null, "file:///secret"));

        assertThat(resolution).isInstanceOf(UnresolvedProbeTarget.class);
        UnresolvedProbeTarget unresolved = (UnresolvedProbeTarget) resolution;
        assertThat(unresolved.result().status()).isEqualTo(ProbeResultStatus.FAILURE);
        assertThat(unresolved.result().reasonCode()).isEqualTo(ProbeReasonCode.INVALID_PROBE_TARGET);
    }

    private ProbeTargetResolver resolver(String defaultBaseUrl, ProbeRegistration... registrations) {
        ProbeRegistry registry = registrations.length == 0 ? null : new ProbeRegistry(List.of(registrations));
        return new ProbeTargetResolver(endpointConfiguration(defaultBaseUrl), registry);
    }

    private ProbeEndpointConfiguration endpointConfiguration(String defaultBaseUrl) {
        ProbeEndpointConfiguration configuration = new ProbeEndpointConfiguration(
                defaultBaseUrl,
                new ProbeEndpointPaths(
                        "/__probe/status",
                        "/__probe/reset",
                        "/__probe/actuate",
                        "/__probe/capture",
                        "/__probe/profiler"),
                new ProbeRequestPolicy(
                        Duration.ofSeconds(1),
                        Duration.ofMillis(100),
                        3,
                        true,
                        2,
                        requestBounds()),
                endpointLimits());
        return configuration;
    }

    private ProbeRequestBounds requestBounds() {
        return new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10);
    }

    private ProbeEndpointLimits endpointLimits() {
        return new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576);
    }
}
