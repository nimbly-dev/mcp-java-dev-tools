package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeMappingResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeMappingsProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Application-owned HTTP adapter for Spring Actuator runtime mappings. */
class RouteSynthesisRuntimeMappingsHttpAdapter implements RouteSynthesisRuntimeMappingsProvider {

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;
    private static final int ABSOLUTE_MAX_RESPONSE_BYTES = 4_194_304;
    private final HttpClient client;
    private final Set<String> allowedHosts;
    private final RouteSynthesisRuntimeMappingsResponseReader responseReader;

    RouteSynthesisRuntimeMappingsHttpAdapter(HttpClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, Set.of("localhost", InetAddress.getLoopbackAddress().getHostAddress()),
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    RouteSynthesisRuntimeMappingsHttpAdapter(
            HttpClient client,
            ObjectMapper objectMapper,
            Collection<String> allowedHosts,
            int maxResponseBytes) {
        this.client = client;
        this.allowedHosts = normalizeHosts(allowedHosts);
        int boundedResponseBytes = Math.max(1, Math.min(maxResponseBytes, ABSOLUTE_MAX_RESPONSE_BYTES));
        this.responseReader = new RouteSynthesisRuntimeMappingsResponseReader(objectMapper, boundedResponseBytes);
    }

    @Override
    public RouteSynthesisRuntimeMappingResolution resolve(
            String mappingsBaseUrl,
            String classHint,
            String methodHint,
            String authToken) {
        URI endpoint;
        try {
            endpoint = normalize(mappingsBaseUrl);
        } catch (RuntimeException exception) {
            return failure("runtime_mappings_input_required", "runtime_mapping_configuration",
                    "provide_mappings_base_url", List.of("mappingsBaseUrl=invalid"));
        }
        if (!this.allowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))) {
            return failure("runtime_mappings_destination_not_allowed", "runtime_mapping_configuration",
                    "allow_runtime_mappings_host", List.of("mappingsDestination=blocked"));
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request(endpoint, authToken), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("runtime_mappings_unreachable", "runtime_mapping_fetch",
                    "verify_runtime_mappings_endpoint", endpointEvidence(endpoint));
        } catch (IOException exception) {
            return failure("runtime_mappings_unreachable", "runtime_mapping_fetch",
                    "verify_runtime_mappings_endpoint", endpointEvidence(endpoint));
        }
        return responseReader.read(response, endpoint, classHint, methodHint);
    }

    private URI normalize(String value) {
        URI uri = URI.create(value == null ? "" : value.trim());
        if (uri.getScheme() == null || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("mappingsBaseUrl must be an absolute HTTP URL without credentials or query");
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path) || "/actuator".equals(path)) {
            path = "/actuator/mappings";
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("mappingsBaseUrl path is not allowed");
        }
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + path);
    }

    private HttpRequest request(URI endpoint, String authToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET();
        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", "Bearer " + authToken.trim());
        }
        return builder.build();
    }

    private Set<String> normalizeHosts(Collection<String> hosts) {
        Set<String> values = new LinkedHashSet<>();
        if (hosts != null) {
            for (String host : hosts) {
                if (host != null && !host.isBlank()) {
                    values.add(host.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(values);
    }

    private List<String> endpointEvidence(URI endpoint) {
        return List.of("mappingsHost=" + endpoint.getHost(), "mappingsPath=" + endpoint.getPath());
    }

    private RouteSynthesisRuntimeMappingResolution failure(
            String reason, String step, String nextAction, List<String> evidence) {
        return RouteSynthesisRuntimeMappingResolution.failure(
                reason, step, nextAction, evidence, List.of("spring_runtime_actuator_mappings"));
    }
}
