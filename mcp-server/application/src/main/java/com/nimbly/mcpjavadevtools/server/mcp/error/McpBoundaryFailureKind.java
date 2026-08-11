package com.nimbly.mcpjavadevtools.server.mcp.error;

/**
 * Closed classifications for unexpected Application Adapter boundary failures.
 */
public enum McpBoundaryFailureKind {

    REQUEST_MAPPING("request_mapping"),
    RESPONSE_MAPPING("response_mapping"),
    CONFIGURATION_INVARIANT("configuration_invariant"),
    FEATURE_INVOCATION_CONTRACT("feature_invocation_contract"),
    PROTOCOL_INTEGRITY("protocol_integrity");

    private final String value;

    McpBoundaryFailureKind(String value) {
        this.value = value;
    }

    /**
     * Returns the safe classification value eligible for MCP metadata.
     *
     * @return stable failure-kind value
     */
    public String value() {
        return value;
    }
}
