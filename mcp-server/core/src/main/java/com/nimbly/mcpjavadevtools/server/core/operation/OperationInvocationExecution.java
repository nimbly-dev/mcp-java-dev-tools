package com.nimbly.mcpjavadevtools.server.core.operation;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes one registered invocation with policy and failure boundaries applied. */
public final class OperationInvocationExecution {

    private static final int MAX_CONCURRENT_OPERATIONS = 16;
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            0,
            MAX_CONCURRENT_OPERATIONS,
            30,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread worker = new Thread(runnable, "mcpjvm-operation");
                worker.setDaemon(true);
                return worker;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private OperationInvocationExecution() {
    }

    public static OperationExecutionResult run(
            OperationManifest manifest, ObjectMapper mapper, OperationInvocation invocation) {
        if (invocation == null || invocation.operationId() == null) {
            return failure(null, OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        OperationRegistration<?, ?> registration = manifest.registration(invocation.operationId());
        if (registration == null) {
            return failure(invocation.operationId(), OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        OperationDescriptor descriptor = registration.descriptor();
        if (descriptor.documentation().deprecated() && !invocation.allowDeprecated()) {
            return failure(invocation.operationId(), OperationExecutionStatus.DEPRECATED_OPERATION,
                    "operation_deprecated", "The operation is deprecated.");
        }
        if (registration.safety().confirmationRequired() && !invocation.confirmed()) {
            return failure(invocation.operationId(), OperationExecutionStatus.CONFIRMATION_REQUIRED,
                    "operation_confirmation_required", "Explicit confirmation is required.");
        }
        JsonNode input = invocation.input();
        try {
            if (mapper.writeValueAsBytes(input).length > registration.safety().maxInputBytes()) {
                return failure(invocation.operationId(), OperationExecutionStatus.INVALID_INPUT,
                        "operation_input_too_large", "The operation input exceeds its safety bound.");
            }
        } catch (Exception exception) {
            return failure(invocation.operationId(), OperationExecutionStatus.INVALID_INPUT,
                    "operation_input_invalid", "The operation input cannot be encoded.");
        }
        List<String> violations = OperationSchemaValidator.violations(registration.inputSchema(), input);
        if (!violations.isEmpty()) {
            return failure(invocation.operationId(), OperationExecutionStatus.INVALID_INPUT,
                    "operation_input_schema_invalid", "The operation input does not match its schema.");
        }
        return execute(registration, mapper, input);
    }

    static OperationExecutionResult execute(
            OperationRegistration<?, ?> registration, ObjectMapper mapper, JsonNode input) {
        Future<JsonNode> task;
        try {
            task = EXECUTOR.submit(() -> registration.execute(input));
        } catch (RejectedExecutionException exception) {
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.FAILED,
                    "operation_execution_capacity", "The operation execution capacity is exhausted.");
        }
        try {
            JsonNode result = task.get(registration.safety().timeoutMillis(), TimeUnit.MILLISECONDS);
            return complete(registration, mapper, result);
        } catch (TimeoutException exception) {
            cancel(task, registration.safety().cancellationSupported());
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.TIMEOUT,
                    "operation_timeout", "The operation exceeded its timeout policy.");
        } catch (InterruptedException exception) {
            cancel(task, registration.safety().cancellationSupported());
            Thread.currentThread().interrupt();
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.CANCELLED,
                    "operation_cancelled", "The operation was interrupted.");
        } catch (ExecutionException exception) {
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.FAILED,
                    "operation_execution_failed", "The operation could not be completed.");
        }
    }

    static void cancel(Future<JsonNode> task, boolean cancellationSupported) {
        task.cancel(cancellationSupported);
    }

    static OperationExecutionResult complete(
            OperationRegistration<?, ?> registration, ObjectMapper mapper, JsonNode result) {
        OperationDescriptor descriptor = registration.descriptor();
        JsonNode safeResult = OperationValueRedactor.redact(result, registration.safety().redactionPolicy());
        try {
            if (mapper.writeValueAsBytes(safeResult).length > registration.safety().maxOutputBytes()) {
                return failure(descriptor.operationId(), OperationExecutionStatus.FAILED,
                        "operation_output_too_large", "The operation result exceeds its safety bound.");
            }
        } catch (Exception exception) {
            return failure(descriptor.operationId(), OperationExecutionStatus.FAILED,
                    "operation_output_invalid", "The operation result cannot be encoded.");
        }
        List<String> violations = OperationSchemaValidator.violations(registration.resultSchema(), safeResult);
        if (!violations.isEmpty()) {
            return failure(descriptor.operationId(), OperationExecutionStatus.FAILED,
                    "operation_result_schema_invalid", "The operation result does not match its schema.");
        }
        return new OperationExecutionResult(
                descriptor.operationId(),
                OperationExecutionStatus.SUCCEEDED,
                safeResult,
                "operation_succeeded",
                "Operation completed.",
                Map.of());
    }

    static OperationExecutionResult failure(
            OperationId operationId,
            OperationExecutionStatus status,
            String reasonCode,
            String reason) {
        return new OperationExecutionResult(operationId, status, null, reasonCode, reason, Map.of());
    }
}
