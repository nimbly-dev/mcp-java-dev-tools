package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Binds both Failure Analysis owners and both verification variants. */
public final class FailureAnalysisOperationRegistrations {

    private FailureAnalysisOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            FailureAnalysisFeature feature, ObjectMapper mapper) {
        Objects.requireNonNull(feature, "failure analysis feature must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        EnumMap<FailureAnalysisAction, Class<?>> requestTypes = new EnumMap<>(FailureAnalysisAction.class);
        requestTypes.put(FailureAnalysisAction.ANALYZE_TRACE, FailureAnalyzeArguments.class);
        requestTypes.put(FailureAnalysisAction.VERIFY_REPRODUCTION, FailureVerifyArguments.class);
        return java.util.Arrays.stream(FailureAnalysisAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, requestTypes.get(action), feature, mapper))
                .toList();
    }

    static <I> OperationRegistration<I, FailureAnalysisResult> register(
            FailureAnalysisAction action, Class<I> requestType,
            FailureAnalysisFeature feature, ObjectMapper mapper) {
        OperationDescriptor descriptor = descriptor(action, requestType, feature);
        return new OperationRegistration<I, FailureAnalysisResult>(
                descriptor,
                requestType,
                FailureAnalysisResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.failure(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> mapper.convertValue(input, requestType),
                input -> feature.execute(decode(action, input)),
                result -> mapper.valueToTree(FailureAnalysisResult.class.cast(result)),
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

    static OperationSchema schema(FailureAnalysisAction action) {
        return action == FailureAnalysisAction.ANALYZE_TRACE ? analyzeSchema() : verifySchema();
    }

    static OperationSchema analyzeSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "trace");
        root.with("properties").with("trace").put("minLength", 1).put("maxLength", 200000)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarBaseUrl").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarAuthorization");
        root.with("properties").with("sidecarAuthorization").put("minLength", 1)
                .put("maxLength", 8192).put("pattern", "\\S");
        root.with("properties").set("investigation", investigationSchema());
        root.with("properties").putObject("timeoutMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 30000);
        CanonicalOperationSchema.required(root, "trace", "sidecarBaseUrl");
        return CanonicalOperationSchema.schema(root);
    }

    static OperationSchema verifySchema() {
        ObjectNode root = verificationRootSchema();
        ObjectNode expected = expectedFingerprintSchema();
        ObjectNode lineHit = lineHitSchema();
        ObjectNode terminal = terminalSchema();
        ObjectNode investigation = investigationSchema();
        root.with("properties").set("expectedFingerprint", expected.deepCopy());
        root.with("properties").set("lineHit", lineHit.deepCopy());
        root.with("properties").set("terminalState", terminal.deepCopy());
        root.with("properties").set("investigation", investigation.deepCopy());
        root.putArray("oneOf")
                .add(runtimeSchema(root, expected, lineHit, investigation))
                .add(terminalVariantSchema(terminal, investigation));
        return CanonicalOperationSchema.schema(root);
    }

    private static ObjectNode verificationRootSchema() {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "captureId").put("minLength", 1).put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarBaseUrl").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(root, "sidecarAuthorization");
        root.with("properties").with("sidecarAuthorization").put("minLength", 1)
                .put("maxLength", 8192).put("pattern", "\\S");
        root.with("properties").putObject("timeoutMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 30000);
        return root;
    }

    private static ObjectNode expectedFingerprintSchema() {
        ObjectNode expected = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(expected, "exceptionType").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(expected, "rootCauseType").put("minLength", 1)
                .put("pattern", "\\S");
        CanonicalOperationSchema.string(expected, "nearestApplicationMethodKey")
                .put("minLength", 1).put("pattern", "\\S");
        CanonicalOperationSchema.required(expected, "exceptionType", "rootCauseType",
                "nearestApplicationMethodKey");
        return expected;
    }

    private static ObjectNode lineHitSchema() {
        ObjectNode lineHit = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(lineHit, "strictLineKey").put("minLength", 1)
                .put("pattern", "\\S");
        lineHit.with("properties").putObject("hitCount").put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.required(lineHit, "strictLineKey", "hitCount");
        return lineHit;
    }

    private static ObjectNode runtimeSchema(
            ObjectNode root, ObjectNode expected, ObjectNode lineHit, ObjectNode investigation) {
        ObjectNode runtime = CanonicalOperationSchema.object();
        runtime.with("properties").putObject("captureId").put("type", "string");
        runtime.with("properties").set("expectedFingerprint", expected.deepCopy());
        runtime.with("properties").set("lineHit", lineHit.deepCopy());
        runtime.with("properties").set("sidecarBaseUrl",
                root.path("properties").path("sidecarBaseUrl").deepCopy());
        runtime.with("properties").set("sidecarAuthorization",
                root.path("properties").path("sidecarAuthorization").deepCopy());
        runtime.with("properties").putObject("timeoutMs").put("type", "integer")
                .put("minimum", 1000).put("maximum", 30000);
        runtime.with("properties").set("investigation", investigation.deepCopy());
        CanonicalOperationSchema.required(runtime, "captureId", "expectedFingerprint", "lineHit",
                "sidecarBaseUrl");
        return runtime;
    }

    private static ObjectNode terminalVariantSchema(
            ObjectNode terminal, ObjectNode investigation) {
        ObjectNode variant = CanonicalOperationSchema.object();
        variant.with("properties").set("terminalState", terminal.deepCopy());
        variant.with("properties").set("investigation", investigation.deepCopy());
        CanonicalOperationSchema.required(variant, "terminalState");
        return variant;
    }

    static ObjectNode investigationSchema() {
        ObjectNode investigation = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(investigation, "mode", "guided", "hands_off");
        investigation.with("properties").putObject("attemptLimit")
                .put("type", "integer").put("minimum", 1).put("maximum", 10);
        investigation.with("properties").putObject("elapsedTimeLimitMs")
                .put("type", "integer").put("minimum", 1000).put("maximum", 300000);
        CanonicalOperationSchema.required(investigation, "mode", "attemptLimit", "elapsedTimeLimitMs");
        return investigation;
    }

    static ObjectNode terminalSchema() {
        ObjectNode terminal = CanonicalOperationSchema.object();
        CanonicalOperationSchema.enumString(terminal, "outcome", "BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH",
                "BLOCKED_MISSING_TRIGGER", "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION",
                "ENVIRONMENT_MISMATCH", "INCONCLUSIVE", "CANCELLED");
        CanonicalOperationSchema.string(terminal, "reasonCode").put("minLength", 1)
                .put("maxLength", 120).put("pattern", "\\S");
        CanonicalOperationSchema.enumString(terminal, "cleanupStatus",
                "cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned");
        terminal.with("properties").putObject("attemptCount").put("type", "integer")
                .put("minimum", 0).put("maximum", 10);
        CanonicalOperationSchema.required(terminal, "outcome", "reasonCode", "cleanupStatus", "attemptCount");
        return terminal;
    }

    static OperationDescriptor descriptor(
            FailureAnalysisAction action, Class<?> requestType, FailureAnalysisFeature feature) {
        String id = OperationId.fromLegacy("failure_analysis", action.value()).value();
        String owner = feature.getClass().getName() + "#execute";
        return new OperationDescriptor(
                "failure_analysis", action.value(), requestType.getName(),
                FailureAnalysisResult.class.getName(), owner,
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        FailureAnalysisOperationRegistrations.class.getName(),
                        FailureAnalysisFeature.class.getName(),
                        FailureAnalysisOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        CoreOperationSafetyPolicy.sideEffect(id),
                        Map.of("featureOwner", feature.getClass().getName(),
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
}
