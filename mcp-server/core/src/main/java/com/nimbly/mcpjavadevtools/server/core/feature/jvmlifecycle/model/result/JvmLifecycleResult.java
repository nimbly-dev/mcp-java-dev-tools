package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic Core result for one JVM lifecycle action.
 *
 * @param status stable result status
 * @param reasonCode stable or helper-owned reason code
 * @param actionResult structured action output when available
 */
public record JvmLifecycleResult(
        JvmLifecycleResultStatus status,
        String reasonCode,
        Optional<JvmLifecycleActionResult> actionResult) {

    /** Validates the deterministic result invariant. */
    public JvmLifecycleResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(actionResult, "actionResult must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 128) {
            throw new IllegalArgumentException("reasonCode is outside the supported bounds");
        }
    }

    /** Creates a blocked report without action-specific output. */
    public static JvmLifecycleResult blocked(String reasonCode) {
        return new JvmLifecycleResult(
                JvmLifecycleResultStatus.BLOCKED,
                reasonCode,
                Optional.empty());
    }

    /** Creates a successful or blocked discovery result. */
    public static JvmLifecycleResult discovery(String reasonCode, JvmListResult result) {
        return new JvmLifecycleResult(
                JvmLifecycleResultStatus.OK,
                reasonCode,
                Optional.of(result));
    }

    /** Creates a structured mutation result with the helper's status mapping. */
    public static JvmLifecycleResult mutation(
            JvmLifecycleResultStatus status,
            String reasonCode,
            JvmMutationResult result) {
        return new JvmLifecycleResult(status, reasonCode, Optional.of(result));
    }
}
