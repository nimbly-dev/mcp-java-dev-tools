package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action;

/** Internal action discriminator retained without changing the public MCP schema. */
public enum TransportExecutionAction {

    EXECUTE("execute");

    private final String value;

    TransportExecutionAction(String value) {
        this.value = value;
    }

    /** @return stable internal action value */
    public String value() {
        return value;
    }
}
