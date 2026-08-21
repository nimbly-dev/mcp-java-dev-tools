package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.HttpExecutionBudget;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.protocol.http.ValidatedHttpRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.Objects;

/** HTTP protocol coordinator delegating validation and bounded redirect execution. */
public final class HttpTransportProvider implements TransportProvider {

    private final HttpRequestValidator validator;
    private final HttpRedirectResponseExecutor executor;

    /** Creates a provider from its purpose-owned HTTP collaborators. */
    public HttpTransportProvider(
            HttpRequestValidator validator,
            HttpRedirectResponseExecutor executor) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public TransportProtocol protocol() {
        return TransportProtocol.HTTP;
    }

    /** {@inheritDoc} */
    @Override
    public ExecuteTransportResult execute(ExecuteTransportRequest request) {
        long started = System.nanoTime();
        try {
            ValidatedHttpRequest validated = validator.validate(request);
            HttpExecutionBudget budget = HttpExecutionBudget.startingAt(
                    started, validated.timeoutMillis());
            return executor.execute(validated, budget);
        } catch (HttpRequestValidationException failure) {
            long elapsed = Math.max(1, (System.nanoTime() - started) / 1_000_000);
            return ExecuteTransportResult.blockedInvalid(
                    failure.reasonCode(), failure.getMessage(), request.protocol().value(),
                    elapsed, failure.reasonMeta());
        }
    }
}
