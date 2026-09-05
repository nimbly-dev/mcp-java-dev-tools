package com.nimbly.mcpjavadevtools.server.core.operation.safety;

/** Named hard ceilings enforced by the Spring-independent Core operation kernel. */
public class OperationSafetyLimits {

    public static final int MAX_INPUT_BYTES = 1_048_576;
    public static final int MAX_OUTPUT_BYTES = 4_194_304;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_JSON_NODES = 100_000;
    public static final int MAX_STRING_VALUE_BYTES = 262_144;
    public static final int MAX_PATTERN_INPUT_BYTES = 8_192;
    public static final int MAX_CONCURRENT_EXECUTIONS = 16;
    public static final long MIN_TIMEOUT_MILLIS = 100;
    public static final long MAX_TIMEOUT_MILLIS = 300_000;
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000;
    public static final long CANCELLATION_GRACE_MILLIS = 1_000;

    private OperationSafetyLimits() {
    }
}
