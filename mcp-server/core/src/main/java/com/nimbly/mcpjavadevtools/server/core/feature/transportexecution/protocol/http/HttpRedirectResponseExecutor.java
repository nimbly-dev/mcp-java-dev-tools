package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.HttpExchangeResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.HttpExecutionBudget;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.ValidatedHttpRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes bounded HTTP redirects and normalizes response or runtime outcomes. */
public final class HttpRedirectResponseExecutor {

    private final HttpClient httpClient;
    private final HttpTransportSafetyPolicy safetyPolicy;
    private final HttpSensitiveDataRedactor redactor;

    /** Creates the executor from its transport, redirect-policy, and redaction collaborators. */
    public HttpRedirectResponseExecutor(
            HttpClient httpClient,
            HttpTransportSafetyPolicy safetyPolicy,
            HttpSensitiveDataRedactor redactor) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.safetyPolicy = Objects.requireNonNull(safetyPolicy, "safetyPolicy must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    /** Executes all redirect hops under one operation-wide timeout budget. */
    public ExecuteTransportResult execute(ValidatedHttpRequest initial, HttpExecutionBudget budget) {
        ValidatedHttpRequest current = initial;
        for (int redirects = 0; ; redirects++) {
            HttpExchangeResponse response;
            try {
                response = exchange(current, budget);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return runtime("transport_request_cancelled",
                        "HTTP transport request was cancelled.", current.uri(), budget);
            } catch (HttpTimeoutException exception) {
                return runtime("transport_request_timeout",
                        "HTTP transport request exceeded its timeout.", current.uri(), budget);
            } catch (HttpResponseTooLargeException exception) {
                return runtime("http_response_body_too_large",
                        "HTTP response body exceeds the safety limit.", current.uri(), budget);
            } catch (IOException | IllegalArgumentException exception) {
                return runtime("transport_request_failed",
                        "HTTP transport request failed.", current.uri(), budget);
            }
            if (!isRedirect(response.statusCode()) || response.redirectLocation() == null) {
                return normalizedResponse(response, budget);
            }
            URI target;
            try {
                target = current.uri().resolve(URI.create(response.redirectLocation()));
            } catch (IllegalArgumentException exception) {
                return redirectInvalid("http_redirect_invalid",
                        "HTTP redirect location is invalid.", current.uri(), budget);
            }
            ExecuteTransportResult failure = validateRedirect(current.uri(), target, redirects, budget);
            if (failure != null) {
                return failure;
            }
            current = redirected(current, target, response.statusCode());
        }
    }

    private HttpExchangeResponse exchange(ValidatedHttpRequest request, HttpExecutionBudget budget)
            throws IOException, InterruptedException {
        long remaining = budget.remainingNanos(System.nanoTime());
        if (remaining == 0) {
            throw new HttpTimeoutException("HTTP operation timeout expired");
        }
        byte[] requestBody = request.body();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(Duration.ofNanos(remaining))
                .method(request.method(), requestBody.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(requestBody));
        request.headers().forEach(builder::header);
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(
                builder.build(), ignored -> new BoundedHttpBodySubscriber(
                        HttpTransportSafetyPolicy.MAXIMUM_RESPONSE_BODY_BYTES));
        HttpResponse<byte[]> response = awaitResponse(exchange, budget);
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(",", values)));
        String location = response.headers().firstValue("Location").filter(value -> !value.isBlank()).orElse(null);
        return new HttpExchangeResponse(response.statusCode(), redactor.redactHeaders(headers),
                new String(response.body(), StandardCharsets.UTF_8), location);
    }

    HttpResponse<byte[]> awaitResponse(
            CompletableFuture<HttpResponse<byte[]>> exchange,
            HttpExecutionBudget budget) throws IOException, InterruptedException {
        long remaining = budget.remainingNanos(System.nanoTime());
        if (remaining == 0) {
            exchange.cancel(true);
            throw new HttpTimeoutException("HTTP operation timeout expired");
        }
        try {
            return exchange.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            exchange.cancel(true);
            throw new HttpTimeoutException("HTTP operation timeout expired");
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpResponseTooLargeException tooLarge) {
                throw tooLarge;
            }
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("HTTP transport request failed", cause);
        }
    }

    private ExecuteTransportResult validateRedirect(
            URI current,
            URI target,
            int redirects,
            HttpExecutionBudget budget) {
        if (redirects >= HttpTransportSafetyPolicy.MAXIMUM_REDIRECTS) {
            return runtime("http_redirect_limit_exceeded",
                    "HTTP redirect limit was exceeded.", target, budget);
        }
        String reason = safetyPolicy.invalidTargetReason(target);
        if (reason != null) {
            String message = switch (reason) {
                case "http_scheme_not_allowed" -> "HTTP URL scheme is not allowed.";
                case "http_host_required" -> "HTTP URL host is required.";
                case "http_user_info_not_allowed" -> "HTTP URL user-info is not allowed.";
                case "http_host_not_allowed" -> "HTTP URL host is not allowed by the safety policy.";
                default -> "HTTP URL is invalid.";
            };
            return redirectInvalid(reason, message, target, budget);
        }
        if (safetyPolicy.isHttpsToHttpDowngrade(current, target)) {
            return redirectInvalid("http_redirect_downgrade",
                    "HTTPS to HTTP redirects are not allowed.", target, budget);
        }
        return null;
    }

    private ValidatedHttpRequest redirected(ValidatedHttpRequest current, URI target, int statusCode) {
        Map<String, String> headers = new LinkedHashMap<>(current.headers());
        if (safetyPolicy.isCrossOrigin(current.uri(), target)) {
            headers.keySet().removeIf(name -> {
                String normalized = name.toLowerCase(Locale.ROOT);
                return normalized.equals("authorization") || normalized.equals("proxy-authorization")
                        || normalized.equals("cookie") || normalized.equals("set-cookie");
            });
        }
        boolean changeToGet = (statusCode == 301 || statusCode == 302 || statusCode == 303)
                && !current.method().equals("GET") && !current.method().equals("HEAD");
        return changeToGet
                ? new ValidatedHttpRequest(target, "GET", headers, new byte[0], current.timeoutMillis())
                : new ValidatedHttpRequest(
                        target, current.method(), headers, current.body(), current.timeoutMillis());
    }

    ExecuteTransportResult normalizedResponse(
            HttpExchangeResponse response,
            HttpExecutionBudget budget) {
        String status = response.statusCode() >= 200 && response.statusCode() < 400 ? "pass" : "fail_http";
        String preview = redactor.redactPreview(response.body());
        if (preview.length() > HttpTransportSafetyPolicy.BODY_PREVIEW_CHARACTERS) {
            preview = preview.substring(0, HttpTransportSafetyPolicy.BODY_PREVIEW_CHARACTERS);
        }
        return ExecuteTransportResult.httpResponse(status, "http", response.statusCode(),
                response.headers(), preview, budget.elapsedMillis(System.nanoTime()));
    }

    ExecuteTransportResult redirectInvalid(
            String reasonCode,
            String message,
            URI uri,
            HttpExecutionBudget budget) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failedStep", "redirect");
        if (uri != null && uri.getUserInfo() == null) {
            metadata.put("url", redactor.redactUri(uri));
        }
        return ExecuteTransportResult.blockedInvalid(reasonCode, message, "http",
                budget.elapsedMillis(System.nanoTime()), metadata);
    }

    ExecuteTransportResult runtime(
            String reasonCode,
            String message,
            URI uri,
            HttpExecutionBudget budget) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failedStep", "transport_execute_http");
        if (uri != null && uri.getUserInfo() == null) {
            metadata.put("url", redactor.redactUri(uri));
        }
        return ExecuteTransportResult.blockedRuntime(reasonCode, message, "http",
                budget.elapsedMillis(System.nanoTime()), metadata);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }
}
