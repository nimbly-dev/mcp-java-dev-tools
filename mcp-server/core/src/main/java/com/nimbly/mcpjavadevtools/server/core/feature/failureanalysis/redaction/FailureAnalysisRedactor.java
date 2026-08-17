package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.redaction;

import java.util.regex.Pattern;

/** Small deterministic redaction policy for untrusted Sidecar evidence. */
public final class FailureAnalysisRedactor {

    private static final Pattern SENSITIVE = Pattern.compile(
            "bearer\\s+\\S+|basic\\s+\\S+|(?:password|secret|token|authorization|cookie)\\s*[:=]\\s*\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{40,}\\b");

    private FailureAnalysisRedactor() {
    }

    /**
     * Trims, redacts, and bounds one untrusted diagnostic value.
     *
     * @param value untrusted value
     * @param maximumLength hard bounded display length
     * @return sanitized value or null
     */
    public static String text(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (SENSITIVE.matcher(normalized).find() || LONG_TOKEN.matcher(normalized).find()) {
            return "<redacted>";
        }
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, maximumLength);
    }

    /**
     * Sanitizes a filesystem-like value while retaining only its basename.
     *
     * @param value untrusted path value
     * @param maximumLength hard bounded display length
     * @return sanitized path or null
     */
    public static String path(String value, int maximumLength) {
        String sanitized = text(value, maximumLength);
        if (sanitized == null || "<redacted>".equals(sanitized)) {
            return sanitized;
        }
        String normalized = sanitized.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        if (separator < 0) {
            return normalized;
        }
        String basename = normalized.substring(separator + 1);
        return basename.isEmpty() ? "<path>" : "<path>/" + basename;
    }
}
