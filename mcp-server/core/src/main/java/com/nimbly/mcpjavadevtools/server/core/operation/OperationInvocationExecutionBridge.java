package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationInvocationExecution;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest;
import java.util.Objects;

/** Keeps invocation source access inside the aggregate operation package boundary. */
public class OperationInvocationExecutionBridge {

    private OperationInvocationExecutionBridge() {
    }

    /** Dispatches an invocation without exposing its mutable source tree to callers. */
    public static OperationExecutionResult run(
            OperationManifest manifest, ObjectMapper mapper, OperationInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        return OperationInvocationExecution.run(
                manifest, mapper, invocation.operationId(), invocation.rawInput(),
                invocation.confirmed(), invocation.allowDeprecated());
    }
}
