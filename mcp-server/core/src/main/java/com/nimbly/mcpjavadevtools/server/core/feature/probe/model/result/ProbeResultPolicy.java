package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

/**
 * Canonical public policy for presenting a Probe result at any host boundary.
 *
 * @param status deterministic result status value
 * @param nextActionCode stable next-action code, when prescribed
 * @param nextAction human-readable next action, when prescribed
 */
public record ProbeResultPolicy(
        String status,
        String nextActionCode,
        String nextAction) {

    /**
     * Resolves the stable response policy for one Core reason code.
     *
     * @param reasonCode Core reason code
     * @return canonical response policy
     */
    public static ProbeResultPolicy forReason(ProbeReasonCode reasonCode) {
        if (reasonCode == ProbeReasonCode.SUCCESS) {
            return new ProbeResultPolicy("ok", null, null);
        }
        String status = reasonCode == ProbeReasonCode.PROBE_ID_REQUIRED
                || reasonCode == ProbeReasonCode.PROBE_ID_UNKNOWN
                ? "probe_selection_failed"
                : reasonCode.value();
        return new ProbeResultPolicy(status, nextActionCode(reasonCode), nextAction(reasonCode));
    }

    private static String nextActionCode(ProbeReasonCode reasonCode) {
        return switch (reasonCode) {
            case SUCCESS -> null;
            case PROBE_ID_REQUIRED -> "provide_probe_id";
            case PROBE_ID_UNKNOWN -> "select_registered_probe_id";
            case LINE_KEY_REQUIRED -> "provide_strict_line_key";
            case INVALID_LINE_TARGET -> "align_runtime_and_artifact";
            case PROBE_UNREACHABLE -> "verify_probe_connectivity";
            case CAPTURE_NOT_FOUND -> "request_new_capture";
            case DIAGNOSE_FAILED -> "resolve_probe_diagnostics";
            case WAIT_TIMEOUT, STALE_PROBE_EVIDENCE -> "verify_trigger_path";
            default -> reasonCode.value();
        };
    }

    private static String nextAction(ProbeReasonCode reasonCode) {
        return switch (reasonCode) {
            case PROBE_ID_REQUIRED -> "Provide probeId or baseUrl. Multi-probe profiles require explicit selection.";
            case PROBE_ID_UNKNOWN -> "Use artifact_management with artifactType=probe_config and action=read, then select a valid probeId.";
            case PROBE_UNREACHABLE -> "Verify Probe connectivity and rerun the action.";
            case WAIT_TIMEOUT, STALE_PROBE_EVIDENCE -> "Verify the trigger path or branch, then rerun wait_for_hit.";
            default -> null;
        };
    }
}
