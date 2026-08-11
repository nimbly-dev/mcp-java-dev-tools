package com.nimbly.mcpjavadevtools.server.mcp.error;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Applies common MCP Application boundary handling to typed response mapping.
 *
 * <p>This is deliberately limited to failure normalization. Capability request,
 * feature, and response mapping behavior remains owned by each Tool.</p>
 */
public final class McpBoundaryExecutor {

    private final McpBoundaryExceptionMapper exceptionMapper;

    /** Creates an executor with the standard sanitized failure mapper. */
    public McpBoundaryExecutor() {
        this(new McpBoundaryExceptionMapper());
    }

    /**
     * Creates an executor with an injected failure mapper.
     *
     * @param exceptionMapper neutral failure mapper
     */
    McpBoundaryExecutor(McpBoundaryExceptionMapper exceptionMapper) {
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper must not be null");
    }

    /**
     * Executes one response mapping operation and converts unexpected mapper
     * failures into the Tool-specific response type.
     *
     * @param operation response mapping operation
     * @param responseFailure response-specific boundary failure mapper
     * @param <T> response type
     * @return mapped response or sanitized boundary response
     */
    public <T> T mapResponse(
            Supplier<T> operation,
            Function<McpBoundaryFailure, T> responseFailure) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(responseFailure, "responseFailure must not be null");
        try {
            return operation.get();
        } catch (McpBoundaryException exception) {
            return map(exception, responseFailure);
        } catch (RuntimeException exception) {
            return map(new McpBoundaryException(
                    McpBoundaryFailureKind.RESPONSE_MAPPING,
                    exception), responseFailure);
        }
    }

    /**
     * Maps an already-classified boundary exception into a typed response.
     *
     * @param exception classified boundary exception
     * @param responseFailure response-specific boundary failure mapper
     * @param <T> response type
     * @return sanitized response
     */
    public <T> T map(
            McpBoundaryException exception,
            Function<McpBoundaryFailure, T> responseFailure) {
        Objects.requireNonNull(exception, "exception must not be null");
        Objects.requireNonNull(responseFailure, "responseFailure must not be null");
        return responseFailure.apply(exceptionMapper.map(exception));
    }
}
