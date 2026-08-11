package com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDK HTTP implementation that enforces Core request and response limits.
 */
public class HttpProbeEndpointClient implements ProbeEndpointClient {

    private static final int BUFFER_SIZE = 8_192;

    private final HttpClient httpClient;

    /**
     * Creates a client using the JDK's default HTTP transport.
     */
    public HttpProbeEndpointClient() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /**
     * Creates a client around an explicit JDK transport collaborator.
     *
     * @param httpClient JDK HTTP transport
     */
    public HttpProbeEndpointClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * Exchanges one validated bounded endpoint request.
     *
     * @param request validated endpoint request
     * @return validated bounded endpoint response
     */
    @Override
    public ProbeEndpointResponse exchange(ProbeEndpointRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest(request),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                Map<String, String> headers = responseHeaders(response.headers().map());
                request.configuration().limits().copyHeaders(headers);
                byte[] responseBytes = boundedBody(body, request.configuration().limits().maximumResponsePayloadBytes());
                return new ProbeEndpointResponse(
                        response.statusCode(),
                        headers,
                        new String(responseBytes, StandardCharsets.UTF_8),
                        responseBytes,
                        request.configuration());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProbeEndpointClientException(
                    ProbeEndpointFailureKind.INTERRUPTED,
                    "Probe endpoint invocation was interrupted",
                    exception);
        } catch (IOException exception) {
            throw new ProbeEndpointClientException(
                    ProbeEndpointFailureKind.UNREACHABLE,
                    "Probe endpoint is unreachable",
                    exception);
        }
    }

    private static HttpRequest httpRequest(ProbeEndpointRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(request.endpoint())
                .timeout(request.timeout())
                .method(request.method(), bodyPublisher(request.payload()));
        request.headers().forEach(builder::header);
        return builder.build();
    }

    private static HttpRequest.BodyPublisher bodyPublisher(String payload) {
        if (payload.isEmpty()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8);
    }

    private static Map<String, String> responseHeaders(Map<String, List<String>> rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : rawHeaders.entrySet()) {
            headers.put(header.getKey(), String.join(",", header.getValue()));
        }
        return headers;
    }

    private static byte[] boundedBody(InputStream input, int maximumBytes) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                appendBounded(output, buffer, read, maximumBytes);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ProbeEndpointClientException(
                    ProbeEndpointFailureKind.RESPONSE_READ_FAILED,
                    "Probe endpoint response could not be read",
                    exception);
        }
    }

    private static void appendBounded(
            ByteArrayOutputStream output,
            byte[] buffer,
            int read,
            int maximumBytes) {
        if (output.size() + read > maximumBytes) {
            throw new ProbeEndpointClientException(
                    ProbeEndpointFailureKind.RESPONSE_LIMIT_EXCEEDED,
                    "Probe endpoint response exceeded the configured limit");
        }
        output.write(buffer, 0, read);
    }
}
