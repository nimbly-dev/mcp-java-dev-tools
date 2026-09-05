package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocationExecutionBridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationValueRedactor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationSupport;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSnapshot;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import java.io.IOException;

/** Executes one registered invocation with policy and failure boundaries applied. */
public class OperationInvocationExecution {
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            0,
            OperationSafetyLimits.MAX_CONCURRENT_EXECUTIONS,
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
        if (invocation == null) {
            return failure(null, OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        return OperationInvocationExecutionBridge.run(manifest, mapper, invocation);
    }

    /** Executes an already-materialized input without taking an unbounded pre-validation copy. */
    public static OperationExecutionResult run(
            OperationManifest manifest,
            ObjectMapper mapper,
            OperationId operationId,
            JsonNode input,
            boolean confirmed,
            boolean allowDeprecated) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (operationId == null) {
            return failure(null, OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        OperationRegistration<?, ?> registration = manifest.registration(operationId);
        if (registration == null) {
            return failure(operationId, OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        OperationDescriptor descriptor = registration.descriptor();
        if (descriptor.documentation().deprecated() && !allowDeprecated) {
            return failure(operationId, OperationExecutionStatus.DEPRECATED_OPERATION,
                    "operation_deprecated", "The operation is deprecated.");
        }
        if (registration.safety().confirmationRequired() && !confirmed) {
            return failure(operationId, OperationExecutionStatus.CONFIRMATION_REQUIRED,
                    "operation_confirmation_required", "Explicit confirmation is required.");
        }
        OperationExecutionContext context = OperationExecutionContext.forTimeout(registration.safety().timeoutMillis());
        JsonNode effectiveInput = input == null ? NullNode.getInstance() : input;
        OperationExecutionResult originalStructureFailure = OperationPayloadValidation.inputStructure(
                operationId, effectiveInput, context);
        if (originalStructureFailure != null) {
            return originalStructureFailure;
        }
        JsonNode acceptedInput;
        try {
            acceptedInput = snapshotInput(mapper, effectiveInput, registration);
        } catch (IOException | RuntimeException | StackOverflowError exception) {
            return failure(operationId, OperationExecutionStatus.INVALID_INPUT,
                    "operation_input_invalid", "The operation input cannot be encoded.");
        }
        if (acceptedInput == null) {
            return failure(operationId, OperationExecutionStatus.INVALID_INPUT,
                    "operation_input_too_large", "The operation input exceeds its safety bound.");
        }
        var snapshotFailure = OperationPayloadValidation.input(registration, mapper, acceptedInput, context);
        if (snapshotFailure != null) {
            return snapshotFailure;
        }
        return execute(registration, mapper, acceptedInput, context);
    }

    static JsonNode snapshotInput(
            ObjectMapper mapper,
            JsonNode input,
            OperationRegistration<?, ?> registration) throws IOException {
        return OperationJsonSnapshot.copy(mapper, input, registration.safety().maxInputBytes());
    }

    static OperationExecutionResult timeout(OperationId operationId) {
        return failure(operationId, OperationExecutionStatus.TIMEOUT,
                "operation_timeout", "The operation exceeded its timeout policy.");
    }

    static OperationExecutionResult cancelled(OperationId operationId) {
        return failure(operationId, OperationExecutionStatus.CANCELLED,
                "operation_cooperative_cancellation",
                "The operation cooperatively requested cancellation.");
    }

    static OperationExecutionResult execute(
            OperationRegistration<?, ?> registration,
            ObjectMapper mapper,
            JsonNode input,
            OperationExecutionContext context) {
        Future<JsonNode> task;
        try {
            task = EXECUTOR.submit(() -> {
                try (OperationExecutionContextScope ignored = context.install()) {
                    return registration.execute(input);
                }
            });
            return await(registration, mapper, context, task);
        } catch (RejectedExecutionException exception) {
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.FAILED,
                    "operation_execution_capacity", "The operation execution capacity is exhausted.");
        }
    }

    static OperationExecutionResult await(
            OperationRegistration<?, ?> registration,
            ObjectMapper mapper,
            OperationExecutionContext context,
            Future<JsonNode> task) {
        try {
            JsonNode result = task.get(context.remainingMillis(), TimeUnit.MILLISECONDS);
            if (context.deadlineExpired()) {
                cancel(task, context, registration);
                return timeout(registration.descriptor().operationId());
            }
            if (context.cancellationRequested()) {
                cancel(task, context, registration);
                return cancelled(registration.descriptor().operationId());
            }
            return complete(registration, mapper, result, context);
        } catch (TimeoutException exception) {
            cancel(task, context, registration);
            return timeout(registration.descriptor().operationId());
        } catch (InterruptedException exception) {
            cancel(task, context, registration);
            Thread.currentThread().interrupt();
            return failure(registration.descriptor().operationId(), OperationExecutionStatus.CANCELLED,
                    "operation_caller_interrupted", "The operation caller was interrupted.");
        } catch (CancellationException exception) {
            return cancelled(registration.descriptor().operationId());
        } catch (ExecutionException exception) {
            if (context.deadlineExpired()) {
                return timeout(registration.descriptor().operationId());
            }
            if (context.cancellationRequested()) {
                return cancelled(registration.descriptor().operationId());
            }
            return failureFromCause(registration.descriptor().operationId(), exception.getCause());
        }
    }

    static void cancel(
            Future<JsonNode> task,
            OperationExecutionContext context,
            OperationRegistration<?, ?> registration) {
        OperationCancellationState state = OperationCancellationSupport.state(
                registration.executor(), registration.safety());
        if (state == OperationCancellationState.CONTEXT_AWARE_CANCELLATION
                || state == OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION) {
            context.requestCancellation();
            awaitCancellation(task);
            if (!task.isDone()) {
                task.cancel(true);
            }
            return;
        }
        task.cancel(state == OperationCancellationState.LEGACY_UNVERIFIED_CANCELLATION);
    }

    static void awaitCancellation(Future<JsonNode> task) {
        try {
            task.get(OperationSafetyLimits.CANCELLATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            task.cancel(true);
        } catch (ExecutionException | TimeoutException | CancellationException ignored) {
            // Cancellation is best effort; the operation result remains a timeout outcome.
        }
    }

    static OperationExecutionResult complete(
            OperationRegistration<?, ?> registration,
            ObjectMapper mapper,
            JsonNode result,
            OperationExecutionContext context) {
        OperationDescriptor descriptor = registration.descriptor();
        OperationExecutionResult preflight = OperationPayloadValidation.output(
                registration, mapper, result, false, context);
        if (preflight != null) {
            return preflight;
        }
        JsonNode safeResult;
        try {
            safeResult = OperationValueRedactor.redact(
                    result, registration.safety().redactionPolicy(), context::cancellationRequested);
            if (safeResult == null) {
                return context.deadlineExpired() ? timeout(descriptor.operationId())
                        : cancelled(descriptor.operationId());
            }
        } catch (RuntimeException exception) {
            return failure(descriptor.operationId(), OperationExecutionStatus.FAILED,
                    "operation_redaction_failed", "The operation result could not be redacted safely.");
        }
        OperationExecutionResult validation = OperationPayloadValidation.output(
                registration, mapper, safeResult, true, context);
        if (validation != null) {
            return validation;
        }
        return new OperationExecutionResult(
                descriptor.operationId(),
                OperationExecutionStatus.SUCCEEDED,
                safeResult,
                "operation_succeeded",
                "Operation completed.",
                Map.of());
    }

    static OperationExecutionResult failureFromCause(OperationId operationId, Throwable cause) {
        String reasonCode = "operation_execution_failed";
        if (cause instanceof OperationFailureException classified) {
            reasonCode = classified.reasonCode();
        }
        return failure(operationId, OperationExecutionStatus.FAILED, reasonCode,
                "The operation could not be completed.");
    }

    static OperationExecutionResult failure(
            OperationId operationId,
            OperationExecutionStatus status,
            String reasonCode,
            String reason) {
        return new OperationExecutionResult(operationId, status, null, reasonCode, reason, Map.of());
    }
}
