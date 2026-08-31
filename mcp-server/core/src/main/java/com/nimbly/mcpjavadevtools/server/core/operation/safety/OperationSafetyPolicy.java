package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import java.util.Objects;

/** Explicit safety, size, timeout, and cancellation policy for one operation. */
public record OperationSafetyPolicy(
        String sideEffect,
        boolean confirmationRequired,
        String credentialPolicy,
        String redactionPolicy,
        long timeoutMillis,
        boolean cancellationSupported,
        int maxInputBytes,
        int maxOutputBytes) {

    /** Validates the bounded execution policy. */
    public OperationSafetyPolicy {
        Objects.requireNonNull(sideEffect, "sideEffect must not be null");
        Objects.requireNonNull(credentialPolicy, "credentialPolicy must not be null");
        Objects.requireNonNull(redactionPolicy, "redactionPolicy must not be null");
        if (sideEffect.isBlank() || credentialPolicy.isBlank() || redactionPolicy.isBlank()
                || sideEffect.length() > 128 || credentialPolicy.length() > 128
                || redactionPolicy.length() > 128) {
            throw new IllegalArgumentException("operation safety policy values must not be blank");
        }
        if (timeoutMillis < 1 || timeoutMillis > 300_000) {
            throw new IllegalArgumentException("timeoutMillis is outside the supported bounds");
        }
        if (maxInputBytes < 1 || maxInputBytes > 4_194_304
                || maxOutputBytes < 1 || maxOutputBytes > 16_777_216) {
            throw new IllegalArgumentException("operation byte bounds are outside the supported bounds");
        }
    }

    /** Creates safe compatibility defaults for an existing descriptor. */
    public static OperationSafetyPolicy legacy(String sideEffect) {
        return new OperationSafetyPolicy(
                sideEffect,
                false,
                "caller_must_not_supply_credentials",
                "redact_sensitive_fields",
                60_000,
                true,
                1_048_576,
                4_194_304);
    }

    /** @return whether the operation is classified as read-only */
    public boolean readOnly() {
        return "none".equals(sideEffect) || "read_only".equals(sideEffect);
    }
}
