package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalized Strict Line Key in {@code class#method:line} form.
 *
 * @param value normalized Strict Line Key value
 */
public record StrictLineKey(String value) {

    private static final Pattern STRICT_LINE_KEY = Pattern.compile(
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*#"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>):[1-9]\\d*$");

    /**
     * Normalizes and validates the record's public value invariant.
     */
    public StrictLineKey {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (!STRICT_LINE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a Strict Line Key");
        }
    }

    /**
     * Parses a key without throwing for invalid user-controlled input.
     *
     * @param rawKey input key
     * @return normalized Strict Line Key when valid
     */
    public static Optional<StrictLineKey> parse(String rawKey) {
        if (rawKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StrictLineKey(rawKey));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a method key plus an optional line hint to a Strict Line Key.
     *
     * @param key strict key or method key
     * @param lineHint line number used when the key is not already strict
     * @return normalized Strict Line Key when the values form one
     */
    public static Optional<StrictLineKey> resolve(String key, Integer lineHint) {
        Optional<StrictLineKey> strictKey = parse(key);
        if (strictKey.isPresent() || key == null || lineHint == null || lineHint <= 0) {
            return strictKey;
        }
        return parse(key.trim() + ":" + lineHint);
    }
}
