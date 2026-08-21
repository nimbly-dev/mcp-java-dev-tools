package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Hard HTTP target and method policy for the Core provider. */
public record HttpTransportSafetyPolicy(Set<String> additionalAllowedHosts) {

    public static final int DEFAULT_TIMEOUT_MILLIS = 20_000;
    public static final int MAXIMUM_TIMEOUT_MILLIS = 300_000;
    public static final int MAXIMUM_REDIRECTS = 5;
    public static final int MAXIMUM_REQUEST_BODY_BYTES = 4 * 1024 * 1024;
    public static final int MAXIMUM_RESPONSE_BODY_BYTES = 4 * 1024 * 1024;
    public static final int MAXIMUM_REQUEST_HEADER_BYTES = 16 * 1024 * 1024;
    public static final int MAXIMUM_HEADER_BYTES = 64 * 1024;
    public static final int MAXIMUM_HEADER_COUNT = 128;
    public static final int BODY_PREVIEW_CHARACTERS = 2_048;

    /** Normalize configured host values while keeping the policy immutable. */
    public HttpTransportSafetyPolicy {
        additionalAllowedHosts = normalizeHosts(additionalAllowedHosts);
    }

    /** @return default loopback hosts plus configured additional hosts */
    public Set<String> allowedHosts() {
        Set<String> values = new LinkedHashSet<>(Set.of("localhost", loopbackIpv4(), loopbackIpv6()));
        values.addAll(additionalAllowedHosts);
        return Set.copyOf(values);
    }

    /**
     * Returns a stable reason code when the URI is outside the target policy.
     *
     * @param uri parsed target URI
     * @return failure reason, or null when the URI is allowed
     */
    public String invalidTargetReason(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            return "http_url_invalid";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "http_scheme_not_allowed";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "http_host_required";
        }
        if (uri.getUserInfo() != null) {
            return "http_user_info_not_allowed";
        }
        return allowedHosts().contains(normalizeHost(uri.getHost())) ? null : "http_host_not_allowed";
    }

    /** @return true when the method is allowed by the HTTP envelope */
    public boolean isAllowedMethod(String method) {
        return Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method);
    }

    /** @return true when the redirect changes origin */
    public boolean isCrossOrigin(URI first, URI second) {
        return !origin(first).equals(origin(second));
    }

    /** @return true when the redirect downgrades HTTPS to HTTP */
    public boolean isHttpsToHttpDowngrade(URI first, URI second) {
        return "https".equalsIgnoreCase(first.getScheme())
                && "http".equalsIgnoreCase(second.getScheme());
    }

    private static Set<String> normalizeHosts(Collection<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return Set.of();
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(normalizeHost(value));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeHost(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String loopbackIpv4() {
        return String.join(".", "127", "0", "0", "1");
    }

    private static String loopbackIpv6() {
        return String.join(":", "", "", "1");
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getScheme().toLowerCase(Locale.ROOT)
                + "://"
                + normalizeHost(uri.getHost())
                + ":"
                + port;
    }
}
