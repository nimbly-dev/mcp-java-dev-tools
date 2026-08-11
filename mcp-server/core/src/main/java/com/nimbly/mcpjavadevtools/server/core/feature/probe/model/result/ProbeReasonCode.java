package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

/**
 * Stable Probe-owned reason codes shared by the foundation and later actions.
 */
public enum ProbeReasonCode {

    SUCCESS("success", ProbeResultStatus.SUCCESS),
    INVALID_REQUEST("invalid_request", ProbeResultStatus.FAILURE),
    SESSION_ID_REQUIRED("session_id_required", ProbeResultStatus.FAILURE),
    RETURN_BOOLEAN_REQUIRED("return_boolean_required", ProbeResultStatus.FAILURE),
    TTL_REQUIRED("ttl_required", ProbeResultStatus.FAILURE),
    DISARM_FIELDS_NOT_ALLOWED("disarm_fields_not_allowed", ProbeResultStatus.FAILURE),
    DIAGNOSE_FAILED("diagnose_failed", ProbeResultStatus.FAILURE),
    STATUS_FAILED("status_failed", ProbeResultStatus.FAILURE),
    RESET_FAILED("reset_failed", ProbeResultStatus.FAILURE),
    CAPTURE_NOT_FOUND("capture_not_found", ProbeResultStatus.FAILURE),
    CAPTURE_FAILED("capture_failed", ProbeResultStatus.FAILURE),
    ACTUATION_FAILED("actuation_failed", ProbeResultStatus.FAILURE),
    PROFILER_FAILED("profiler_failed", ProbeResultStatus.FAILURE),
    PROBE_UNREACHABLE("probe_unreachable", ProbeResultStatus.FAILURE),
    WAIT_TIMEOUT("wait_timeout", ProbeResultStatus.FAILURE),
    STALE_PROBE_EVIDENCE("stale_probe_evidence", ProbeResultStatus.FAILURE),
    WAIT_INTERRUPTED("interrupted", ProbeResultStatus.BLOCKED),
    LINE_KEY_REQUIRED("line_key_required", ProbeResultStatus.FAILURE),
    INVALID_LINE_TARGET("invalid_line_target", ProbeResultStatus.FAILURE),
    PROBE_ID_REQUIRED("probe_id_required", ProbeResultStatus.BLOCKED),
    PROBE_ID_UNKNOWN("probe_id_unknown", ProbeResultStatus.BLOCKED),
    INVALID_PROBE_TARGET("invalid_probe_target", ProbeResultStatus.FAILURE);

    private final String value;
    private final ProbeResultStatus resultStatus;

    ProbeReasonCode(String value, ProbeResultStatus resultStatus) {
        this.value = value;
        this.resultStatus = resultStatus;
    }

    /**
     * Returns the stable serialized reason-code value.
     *
     * @return stable reason-code value
     */
    public String value() {
        return value;
    }

    /**
     * Returns the only deterministic result status valid for this reason code.
     *
     * @return valid result status
     */
    public ProbeResultStatus resultStatus() {
        return resultStatus;
    }
}
