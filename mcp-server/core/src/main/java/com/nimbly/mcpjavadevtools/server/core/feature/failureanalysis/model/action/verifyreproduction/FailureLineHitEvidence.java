package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction;

import java.util.Objects;
import java.util.regex.Pattern;

/** Positive canonical Strict Line Key evidence supplied by the Probe workflow. */
public record FailureLineHitEvidence(String strictLineKey, int hitCount) {

    private static final Pattern STRICT_LINE_KEY = Pattern.compile(
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*#"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>):[1-9]\\d*$");

    public FailureLineHitEvidence {
        Objects.requireNonNull(strictLineKey, "strictLineKey must not be null");
        strictLineKey = strictLineKey.trim();
        if (!STRICT_LINE_KEY.matcher(strictLineKey).matches()) {
            throw new IllegalArgumentException("strictLineKey must be a Strict Line Key");
        }
        if (hitCount <= 0) {
            throw new IllegalArgumentException("hitCount must be positive");
        }
    }
}
