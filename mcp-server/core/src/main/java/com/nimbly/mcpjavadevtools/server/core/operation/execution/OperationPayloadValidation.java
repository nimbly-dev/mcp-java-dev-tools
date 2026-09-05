package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSize;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.BoundedOperationSchemaValidator;
import java.util.List;

/** Applies bounded input and output checks before conversion, copying, and disclosure. */
public class OperationPayloadValidation {

    private OperationPayloadValidation() {
    }

    static OperationExecutionResult input(
            OperationRegistration<?, ?> registration,
            ObjectMapper mapper,
            JsonNode input,
            OperationExecutionContext context) {
        OperationId operationId = registration.descriptor().operationId();
        OperationExecutionResult structuralFailure = inputStructure(operationId, input, context);
        if (structuralFailure != null) {
            return structuralFailure;
        }
        try {
            if (OperationJsonSize.measure(mapper, input, registration.safety().maxInputBytes()) < 0) {
                return OperationInvocationExecution.failure(operationId,
                        OperationExecutionStatus.INVALID_INPUT, "operation_input_too_large",
                        "The operation input exceeds its safety bound.");
            }
        } catch (Exception exception) {
            return OperationInvocationExecution.failure(operationId,
                    OperationExecutionStatus.INVALID_INPUT, "operation_input_invalid",
                    "The operation input cannot be encoded.");
        }
        if (context.deadlineExpired()) {
            return OperationInvocationExecution.timeout(operationId);
        }
        List<String> violations = BoundedOperationSchemaValidator.schemaViolations(
                registration.inputSchema(), input, context::cancellationRequested);
        if (!violations.isEmpty()) {
            return context.deadlineExpired() ? OperationInvocationExecution.timeout(operationId)
                    : OperationInvocationExecution.failure(operationId,
                    OperationExecutionStatus.INVALID_INPUT, "operation_input_schema_invalid",
                    "The operation input does not match its schema.");
        }
        return context.deadlineExpired() ? OperationInvocationExecution.timeout(operationId) : null;
    }

    static OperationExecutionResult inputStructure(
            OperationId operationId, JsonNode input, OperationExecutionContext context) {
        List<String> structural = BoundedOperationSchemaValidator.treeViolations(
                input, context::cancellationRequested);
        if (structural.isEmpty()) {
            return null;
        }
        return context.deadlineExpired() ? OperationInvocationExecution.timeout(operationId)
                : OperationInvocationExecution.failure(operationId,
                OperationExecutionStatus.INVALID_INPUT, "operation_input_structure_invalid",
                "The operation input exceeds its JSON limits.");
    }

    static OperationExecutionResult output(
            OperationRegistration<?, ?> registration,
            ObjectMapper mapper,
            JsonNode result,
            boolean validateSchema,
            OperationExecutionContext context) {
        OperationId operationId = registration.descriptor().operationId();
        if (!BoundedOperationSchemaValidator.treeViolations(
                result, context::cancellationRequested).isEmpty()) {
            if (context.deadlineExpired()) {
                return OperationInvocationExecution.timeout(operationId);
            }
            return OperationInvocationExecution.failure(operationId, OperationExecutionStatus.FAILED,
                    "operation_output_structure_invalid", "The operation result exceeds its JSON limits.");
        }
        try {
            if (OperationJsonSize.measure(mapper, result, registration.safety().maxOutputBytes()) < 0) {
                return OperationInvocationExecution.failure(operationId,
                        OperationExecutionStatus.FAILED, "operation_output_too_large",
                        "The operation result exceeds its safety bound.");
            }
        } catch (Exception exception) {
            return OperationInvocationExecution.failure(operationId,
                    OperationExecutionStatus.FAILED, "operation_output_invalid",
                    "The operation result cannot be encoded.");
        }
        if (context.deadlineExpired()) {
            return OperationInvocationExecution.timeout(operationId);
        }
        if (validateSchema && !BoundedOperationSchemaValidator.schemaViolations(
                registration.resultSchema(), result, context::cancellationRequested).isEmpty()) {
            if (context.deadlineExpired()) {
                return OperationInvocationExecution.timeout(operationId);
            }
            return OperationInvocationExecution.failure(operationId,
                    OperationExecutionStatus.FAILED, "operation_result_schema_invalid",
                    "The operation result does not match its schema.");
        }
        return context.deadlineExpired() ? OperationInvocationExecution.timeout(operationId) : null;
    }
}
