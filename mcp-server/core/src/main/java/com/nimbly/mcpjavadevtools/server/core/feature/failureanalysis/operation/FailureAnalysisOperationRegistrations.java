package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.DefaultFailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.VerifyReproductionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureAnalyzeArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureExpectedFingerprintArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureLineHitArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureTerminalArguments;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation.FailureVerifyArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds both Failure Analysis owners and both verification variants. */
public class FailureAnalysisOperationRegistrations {

    private FailureAnalysisOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            FailureAnalysisFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        var defaultFeature = (DefaultFailureAnalysisFeature) Objects.requireNonNull(feature, "feature must not be null");
        return List.of(
                register(FailureAnalysisAction.ANALYZE_TRACE, FailureAnalyzeArguments.class, defaultFeature, mapper),
                register(FailureAnalysisAction.VERIFY_REPRODUCTION, FailureVerifyArguments.class, defaultFeature, mapper));
    }

    static <I> OperationRegistration<I, FailureAnalysisResult> register(
            FailureAnalysisAction action, Class<I> requestType,
            DefaultFailureAnalysisFeature feature, ObjectMapper mapper) {
        FailureAnalysisActionHandler owner = feature.operationOwner(action);
        OperationDescriptor descriptor = descriptor(action, requestType, owner);
        return new OperationRegistration<I, FailureAnalysisResult>(
                descriptor,
                requestType,
                FailureAnalysisResult.class,
                new OperationRegistrationContract(
                        FailureAnalysisOperationSchemas.forAction(action),
                        FailureAnalysisResultSchema.create(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                OperationRequestDecoders.typed(mapper.copy()
                                .setSerializationInclusion(JsonInclude.Include.NON_NULL), requestType),
                ContextAwareOperationExecutor.declared(OperationCancellationState.CONTEXT_AWARE_CANCELLATION,
                        OperationCancellationGuarantee.IDEMPOTENT,
                        (input, context) -> {
                            checkpoint(context);
                            FailureAnalysisResult result = owner.execute(decode(action, input));
                            checkpoint(context);
                            return result;
                        }),
                OperationResultEncoders.typed(mapper, FailureAnalysisResult.class),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static FailureAnalysisRequest decode(FailureAnalysisAction action, Object input) {
        if (action == FailureAnalysisAction.ANALYZE_TRACE) {
            FailureAnalyzeArguments arguments = (FailureAnalyzeArguments) input;
            FailureInvestigationContext investigation = arguments.investigation() == null ? null
                    : new FailureInvestigationContext(
                            arguments.investigation().mode(),
                            arguments.investigation().attemptLimit(),
                            arguments.investigation().elapsedTimeLimitMs());
            Duration timeout = arguments.timeoutMs() == null ? null : Duration.ofMillis(arguments.timeoutMs());
            return new AnalyzeTraceRequest(arguments.trace(), validUrl(arguments.sidecarBaseUrl()),
                    arguments.sidecarAuthorization(), investigation, timeout);
        }
        FailureVerifyArguments arguments = (FailureVerifyArguments) input;
        FailureInvestigationContext investigation = arguments.investigation() == null ? null
                : new FailureInvestigationContext(
                        arguments.investigation().mode(),
                        arguments.investigation().attemptLimit(),
                        arguments.investigation().elapsedTimeLimitMs());
        if (arguments.terminalState() != null) {
            FailureTerminalArguments terminal = arguments.terminalState();
            return new VerifyReproductionRequest(null, null, null, null, null, investigation, null,
                    new FailureTerminalState(terminal.outcome(), terminal.reasonCode(),
                            terminal.cleanupStatus(), terminal.attemptCount()));
        }
        FailureExpectedFingerprintArguments expected = arguments.expectedFingerprint();
        FailureLineHitArguments lineHit = arguments.lineHit();
        return new VerifyReproductionRequest(
                arguments.captureId(),
                FailureFingerprint.expected(expected.exceptionType(), expected.rootCauseType(),
                        expected.nearestApplicationMethodKey()),
                new FailureLineHitEvidence(lineHit.strictLineKey(), lineHit.hitCount()),
                validUrl(arguments.sidecarBaseUrl()),
                arguments.sidecarAuthorization(),
                investigation,
                arguments.timeoutMs() == null ? null : Duration.ofMillis(arguments.timeoutMs()),
                null);
    }

    static OperationDescriptor descriptor(
            FailureAnalysisAction action, Class<?> requestType, FailureAnalysisActionHandler owner) {
        String id = OperationId.fromLegacy("failure_analysis", action.value()).value();
        String executableOwner = owner.getClass().getName() + "#execute";
        return new OperationDescriptor(
                "failure_analysis", action.value(), requestType.getName(),
                FailureAnalysisResult.class.getName(), executableOwner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        FailureAnalysisOperationRegistrations.class.getName(),
                        FailureAnalysisActionHandler.class.getName(),
                        FailureAnalysisOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("executableOwner", executableOwner,
                                "operationId", id,
                                "requestType", requestType.getName())));
    }

    static OperationLegacyIdentity identity(FailureAnalysisAction action) {
        return new OperationLegacyIdentity(
                "failure_analysis", action.value(), false, Map.of(),
                "failure_analysis_action_to_typed_evidence_request",
                "failure_outcome_fingerprint_line_hit_and_attempt_evidence_preserved",
                "failure_analysis_" + action.value());
    }

    static String validUrl(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        try {
            URI uri = URI.create(normalized);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new IllegalArgumentException("sidecarBaseUrl must be an absolute URL");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sidecarBaseUrl must be a valid URL", exception);
        }
    }

    private static void checkpoint(OperationExecutionContext context) {
        if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
            throw new java.util.concurrent.CancellationException("failure analysis operation was cancelled");
        }
    }
}
