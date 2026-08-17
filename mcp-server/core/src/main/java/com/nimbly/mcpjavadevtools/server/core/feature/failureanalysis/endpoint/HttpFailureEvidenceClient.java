package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerifyEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** JDK HTTP client for bounded, non-redirecting Sidecar Failure Lens calls. */
public final class HttpFailureEvidenceClient implements FailureEvidenceClient {

    private static final int BUFFER_SIZE = 8_192;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FailureAnalysisPolicy policy;

    public HttpFailureEvidenceClient(ObjectMapper objectMapper, FailureAnalysisPolicy policy) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper, policy);
    }

    public HttpFailureEvidenceClient(
            HttpClient httpClient, ObjectMapper objectMapper, FailureAnalysisPolicy policy) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Override
    public FailureEvidenceResponse analyze(FailureAnalyzeEvidenceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return send(request.baseUrl(), "/__probe/failure/analyze", Map.of("trace", request.trace()),
                request.authorization(), request.timeout());
    }

    @Override
    public FailureEvidenceResponse verify(FailureVerifyEvidenceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captureId", request.captureId());
        payload.put("expectedExceptionType", request.expectedExceptionType());
        payload.put("expectedRootCauseType", request.expectedRootCauseType());
        payload.put("expectedNearestApplicationMethodKey", request.expectedNearestApplicationMethodKey());
        return send(request.baseUrl(), "/__probe/failure/verify", payload,
                request.authorization(), request.timeout());
    }

    private FailureEvidenceResponse send(
            String baseUrl, String path, Map<String, Object> payload, String authorization, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl, path))
                    .timeout(timeout)
                    .header("content-type", "application/json");
            if (authorization != null && !authorization.trim().isEmpty()) {
                builder.header("Authorization", authorization.trim());
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(writePayload(payload), StandardCharsets.UTF_8))
                    .build();
            return exchange(request);
        } catch (FailureEvidenceClientException exception) {
            throw exception;
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.INVALID_ENDPOINT,
                    "Sidecar failure endpoint is invalid", exception);
        }
    }

    private FailureEvidenceResponse exchange(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return new FailureEvidenceResponse(response.statusCode(), parseBody(boundedBody(body)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.INTERRUPTED,
                    "Sidecar failure endpoint invocation was interrupted", exception);
        } catch (HttpTimeoutException exception) {
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.TIMEOUT,
                    "Sidecar failure endpoint invocation timed out", exception);
        } catch (IOException exception) {
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.UNREACHABLE,
                    "Sidecar failure endpoint is unreachable", exception);
        }
    }

    private URI endpoint(String baseUrl, String path) throws URISyntaxException {
        URI base = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null) {
            throw new URISyntaxException(baseUrl, "unsupported or unsafe endpoint URI");
        }
        return base.resolve(path.substring(1));
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.REQUEST_SERIALIZATION_FAILED,
                    "Sidecar failure request could not be serialized", exception);
        }
    }

    private JsonNode parseBody(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(body);
            return value != null && value.isObject() ? value : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private byte[] boundedBody(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > policy.maximumResponsePayloadBytes()) {
                    throw new FailureEvidenceClientException(
                            FailureEvidenceFailureKind.RESPONSE_LIMIT_EXCEEDED,
                            "Sidecar failure response exceeded the configured limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (FailureEvidenceClientException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.RESPONSE_READ_FAILED,
                    "Sidecar failure response could not be read", exception);
        }
    }

}
