package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, Feature-owned Probe result.
 *
 * @param status completion state
 * @param reasonCode stable Probe-owned reason code
 * @param reasonMetadata bounded safe outcome metadata
 * @param actionResult optional typed result produced by a completed action
 */
public record ProbeResult(
        ProbeResultStatus status,
        ProbeReasonCode reasonCode,
        ProbeReasonMetadata reasonMetadata,
        Optional<ProbeActionResult> actionResult) {

    /**
     * Validates the invariant required by deterministic result mapping.
     */
    public ProbeResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(reasonMetadata, "reasonMetadata must not be null");
        Objects.requireNonNull(actionResult, "actionResult must not be null");
        validateReasonCode(status, reasonCode);
    }

    /**
     * Preserves the foundation result shape when no action-specific output exists.
     *
     * @param status completion state
     * @param reasonCode stable Probe-owned reason code
     * @param reasonMetadata bounded safe outcome metadata
     */
    public ProbeResult(
            ProbeResultStatus status,
            ProbeReasonCode reasonCode,
            ProbeReasonMetadata reasonMetadata) {
        this(status, reasonCode, reasonMetadata, Optional.empty());
    }

    /**
     * Creates a successful result with no unsafe metadata.
     *
     * @return successful result
     */
    public static ProbeResult success() {
        return new ProbeResult(
                ProbeResultStatus.SUCCESS,
                ProbeReasonCode.SUCCESS,
                ProbeReasonMetadata.empty());
    }

    /**
     * Creates a successful result with a typed action outcome.
     *
     * @param actionResult typed completed-action result
     * @return successful result
     */
    public static ProbeResult success(ProbeActionResult actionResult) {
        return withActionResult(ProbeReasonCode.SUCCESS, ProbeReasonMetadata.empty(), actionResult);
    }

    /**
     * Creates an expected invalid or failed outcome.
     *
     * @param reasonCode stable failure reason code
     * @param metadata bounded safe metadata
     * @return failed result
     */
    public static ProbeResult failure(ProbeReasonCode reasonCode, ProbeReasonMetadata metadata) {
        return new ProbeResult(ProbeResultStatus.FAILURE, reasonCode, metadata);
    }

    /**
     * Creates an expected blocked outcome.
     *
     * @param reasonCode stable blocked reason code
     * @param metadata bounded safe metadata
     * @return blocked result
     */
    public static ProbeResult blocked(ProbeReasonCode reasonCode, ProbeReasonMetadata metadata) {
        return new ProbeResult(ProbeResultStatus.BLOCKED, reasonCode, metadata);
    }

    /**
     * Attaches a typed action result to a deterministic outcome.
     *
     * @param reasonCode stable outcome reason code
     * @param metadata bounded safe metadata
     * @param actionResult typed action result
     * @return deterministic outcome with action data
     */
    public static ProbeResult withActionResult(
            ProbeReasonCode reasonCode,
            ProbeReasonMetadata metadata,
            ProbeActionResult actionResult) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        return new ProbeResult(
                reasonCode.resultStatus(),
                reasonCode,
                metadata,
                Optional.of(Objects.requireNonNull(actionResult, "actionResult must not be null")));
    }

    /**
     * Returns the canonical host-boundary presentation policy for this result.
     *
     * @return stable result status and next-action policy
     */
    public ProbeResultPolicy policy() {
        return ProbeResultPolicy.forReason(reasonCode);
    }

    private static void validateReasonCode(ProbeResultStatus status, ProbeReasonCode reasonCode) {
        if (status != reasonCode.resultStatus()) {
            throw new IllegalArgumentException("result status does not match the reason code");
        }
    }
}
