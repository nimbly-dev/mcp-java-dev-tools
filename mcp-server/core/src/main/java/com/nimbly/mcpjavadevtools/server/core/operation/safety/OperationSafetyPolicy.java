package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import java.util.Objects;
import java.util.Set;

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

    private static final Set<String> READ_ONLY_SIDE_EFFECTS = Set.of(
            "none", "read_only", "filesystem_read", "sqlite_read", "jvm_process_read",
            "probe_endpoint_read", "probe_endpoint_poll", "probe_capture_read", "evidence_read");

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
        if (timeoutMillis < OperationSafetyLimits.MIN_TIMEOUT_MILLIS
                || timeoutMillis > OperationSafetyLimits.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis is outside the supported bounds");
        }
        if (maxInputBytes < 1 || maxInputBytes > OperationSafetyLimits.MAX_INPUT_BYTES
                || maxOutputBytes < 1 || maxOutputBytes > OperationSafetyLimits.MAX_OUTPUT_BYTES) {
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
                OperationSafetyLimits.DEFAULT_TIMEOUT_MILLIS,
                true,
                OperationSafetyLimits.MAX_INPUT_BYTES,
                OperationSafetyLimits.MAX_OUTPUT_BYTES);
    }

    /** @return whether the operation is classified as read-only */
    public boolean readOnly() {
        return READ_ONLY_SIDE_EFFECTS.contains(sideEffect);
    }
}
