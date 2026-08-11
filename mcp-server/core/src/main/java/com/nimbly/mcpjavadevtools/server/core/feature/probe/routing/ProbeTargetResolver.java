package com.nimbly.mcpjavadevtools.server.core.feature.probe.routing;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSource;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves Probe targets with TypeScript-compatible selection precedence.
 *
 * <p>An explicit {@code probeId} always wins over {@code baseUrl}; a direct
 * {@code baseUrl} wins over implicit registry and configured-default selection.</p>
 */
public class ProbeTargetResolver {

    private final ProbeEndpointConfiguration endpointConfiguration;
    private final ProbeRegistryProvider probeRegistryProvider;

    /**
     * Creates a resolver backed by a fixed registry for Core callers and tests.
     *
     * @param endpointConfiguration endpoint policy
     * @param probeRegistry fixed registry
     */
    public ProbeTargetResolver(
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeRegistry probeRegistry) {
        this.endpointConfiguration = Objects.requireNonNull(endpointConfiguration);
        this.probeRegistryProvider = () -> probeRegistry;
    }

    /**
     * Creates a resolver backed by a replaceable registry source.
     *
     * @param endpointConfiguration endpoint policy
     * @param probeRegistryProvider current registry source
     */
    public static ProbeTargetResolver dynamic(
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeRegistryProvider probeRegistryProvider) {
        return new ProbeTargetResolver(
                endpointConfiguration,
                Objects.requireNonNull(probeRegistryProvider));
    }

    private ProbeTargetResolver(
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeRegistryProvider probeRegistryProvider) {
        this.endpointConfiguration = Objects.requireNonNull(endpointConfiguration);
        this.probeRegistryProvider = Objects.requireNonNull(probeRegistryProvider);
    }

    /**
     * Resolves a shared target selector without endpoint I/O.
     *
     * @param selector direct and registry target selection input
     * @return deterministic resolved, failed, or blocked target outcome
     */
    public ProbeTargetResolution resolve(ProbeTargetSelector selector) {
        if (selector == null) {
            return unresolved(ProbeResult.failure(
                    ProbeReasonCode.INVALID_REQUEST,
                    ProbeReasonMetadata.inputValidation()));
        }
        if (selector.hasProbeId()) {
            return resolveExplicitProbeId(selector.probeId());
        }
        if (selector.hasBaseUrl()) {
            return resolveBaseUrl(selector.baseUrl(), null, ProbeTargetSource.DIRECT_BASE_URL);
        }
        return resolveImplicitTarget();
    }

    private ProbeTargetResolution resolveExplicitProbeId(String probeId) {
        ProbeRegistry probeRegistry = currentRegistry();
        if (probeRegistry == null) {
            return probeIdRequired(probeId);
        }
        Optional<ProbeRegistration> registration = probeRegistry.findById(probeId);
        if (registration.isEmpty()) {
            return unresolved(ProbeResult.blocked(
                    ProbeReasonCode.PROBE_ID_UNKNOWN,
                    ProbeReasonMetadata.routing(probeId, configuredProbeCount())));
        }
        return resolveRegistration(registration.get(), ProbeTargetSource.PROBE_ID);
    }

    private ProbeTargetResolution resolveImplicitTarget() {
        ProbeRegistry probeRegistry = currentRegistry();
        if (probeRegistry != null) {
            Optional<String> implicitProbeId = probeRegistry.implicitProbeId();
            if (implicitProbeId.isPresent()) {
                ProbeRegistration registration = probeRegistry.findById(implicitProbeId.get()).orElseThrow();
                return resolveRegistration(registration, ProbeTargetSource.REGISTRY_DEFAULT);
            }
        }
        if (endpointConfiguration.hasDefaultBaseUrl()) {
            return resolveBaseUrl(
                    endpointConfiguration.defaultBaseUrl(),
                    null,
                    ProbeTargetSource.CONFIGURED_DEFAULT);
        }
        return probeIdRequired(null);
    }

    private ProbeTargetResolution resolveRegistration(
            ProbeRegistration registration,
            ProbeTargetSource source) {
        return resolveBaseUrl(registration.baseUrl(), registration.id(), source);
    }

    private ProbeTargetResolution resolveBaseUrl(String rawBaseUrl, String probeId, ProbeTargetSource source) {
        try {
            URI baseUrl = URI.create(rawBaseUrl).normalize();
            if (!isHttpBaseUrl(baseUrl)) {
                return invalidTarget(probeId);
            }
            return new ResolvedProbeTarget(new ProbeTarget(probeId, baseUrl, source));
        } catch (IllegalArgumentException exception) {
            return invalidTarget(probeId);
        }
    }

    private boolean isHttpBaseUrl(URI baseUrl) {
        String scheme = baseUrl.getScheme();
        if (scheme == null || baseUrl.getHost() == null) {
            return false;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(normalizedScheme) || "https".equals(normalizedScheme);
    }

    private ProbeTargetResolution invalidTarget(String probeId) {
        return unresolved(ProbeResult.failure(
                ProbeReasonCode.INVALID_PROBE_TARGET,
                ProbeReasonMetadata.targetValidation(probeId, configuredProbeCount())));
    }

    private ProbeTargetResolution probeIdRequired(String probeId) {
        return unresolved(ProbeResult.blocked(
                ProbeReasonCode.PROBE_ID_REQUIRED,
                ProbeReasonMetadata.routing(probeId, configuredProbeCount())));
    }

    private Integer configuredProbeCount() {
        ProbeRegistry probeRegistry = currentRegistry();
        return probeRegistry == null ? null : probeRegistry.size();
    }

    private ProbeRegistry currentRegistry() {
        return probeRegistryProvider.current();
    }

    private static UnresolvedProbeTarget unresolved(ProbeResult result) {
        return new UnresolvedProbeTarget(result);
    }
}
