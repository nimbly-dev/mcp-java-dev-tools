package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.AnalyzeTraceAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.VerifyReproductionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.VerifyReproductionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalysisEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.operation.FailureAnalysisOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailureAnalysisActionHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FailureAnalysisPolicy POLICY = new FailureAnalysisPolicy(
            Duration.ofSeconds(15), 200_000, 65_536, 256, 8, 8);

    @Test
    void requiresExactlyOneHandlerForEveryFailureAnalysisAction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureAnalysisOperationRegistrations.create(
                        List.of(handler(FailureAnalysisAction.ANALYZE_TRACE)), JSON))
                .withMessage("missing action handler: VERIFY_REPRODUCTION");

        FailureAnalysisActionHandler analyze = handler(FailureAnalysisAction.ANALYZE_TRACE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureAnalysisOperationRegistrations.create(List.of(analyze, analyze), JSON))
                .withMessage("duplicate action handler: ANALYZE_TRACE");
    }

    @Test
    void analyzesCompleteFingerprintAndRedactsSensitiveMessage() throws Exception {
        StubClient client = new StubClient(
                new FailureEvidenceResponse(200, JSON.readTree("""
                        {"fingerprint":{"exceptionType":"java.lang.IllegalStateException",
                        "rootCauseType":"java.lang.IllegalStateException",
                        "nearestApplicationFrame":{"className":"example.OrderService",
                        "methodName":"submit","lineNumber":42,"ownership":"application"},
                        "normalizedMessage":"Bearer secret-value","complete":true},
                        "investigationCandidates":[],"exceptionSections":[],"reasons":[]}
                        """)), null);
        FailureAnalysisResult result = analyzeAction(client).execute(new AnalyzeTraceRequest(
                "java.lang.IllegalStateException: failure", "http://sidecar.example", "Bearer secret-value",
                investigation(), Duration.ofSeconds(2)));

        assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.ANALYZED);
        assertThat(result.reasonCode()).isEqualTo("ok");
        assertThat(result.fingerprint().nearestApplicationMethodKey()).isEqualTo("example.OrderService#submit");
        assertThat(result.fingerprint().normalizedMessage()).isEqualTo("<redacted>");
        assertThat(result.toString()).doesNotContain("secret-value");
    }

    @Test
    void blocksDiagnosisWhenFingerprintIsIncomplete() throws Exception {
        StubClient client = new StubClient(
                new FailureEvidenceResponse(200, JSON.readTree("""
                        {"fingerprint":{"exceptionType":"java.lang.IllegalStateException",
                        "complete":false,"incompletenessReasons":["source_line_missing"]}}
                        """)), null);

        FailureAnalysisResult result = analyzeAction(client).execute(new AnalyzeTraceRequest(
                "trace", "http://sidecar.example", null, null, Duration.ofSeconds(2)));

        assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reasonCode()).isEqualTo("failure_fingerprint_incomplete");
        assertThat(result.diagnosisClaimed()).isFalse();
    }

    @Test
    void requiresBothMatchingFingerprintAndPositiveLineHitForReproduction() throws Exception {
        StubClient client = new StubClient(null, new FailureEvidenceResponse(200, JSON.readTree("""
                {"outcome":"matched","observedFingerprint":{"exceptionType":"ExampleFailure",
                "rootCauseType":"ExampleFailure",
                "nearestApplicationFrame":{"className":"example.OrderService",
                "methodName":"submit","lineNumber":42},"complete":true},"reasons":[]}
                """)));
        VerifyReproductionRequest request = new VerifyReproductionRequest(
                "capture-1", FailureFingerprint.expected(
                        "ExampleFailure", "ExampleFailure", "example.OrderService#submit"),
                new FailureLineHitEvidence("example.OrderService#submit:42", 1),
                "http://sidecar.example", null, investigation(), Duration.ofSeconds(2), null);

        FailureAnalysisResult result = verifyAction(client).execute(request);

        assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.REPRODUCED);
        assertThat(result.diagnosisClaimed()).isTrue();
        assertThat(result.lineHit().hitCount()).isEqualTo(1);
    }

    @Test
    void terminalVerificationDoesNotCallSidecar() {
        StubClient client = new StubClient(null, null);
        VerifyReproductionRequest request = new VerifyReproductionRequest(
                null, null, null, null, null, null, null,
                new FailureTerminalState(
                        "BLOCKED_MISSING_AUTH", "missing_auth", "cleanup_confirmed", 1));

        FailureAnalysisResult result = verifyAction(client).execute(request);

        assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.BLOCKED_MISSING_AUTH);
        assertThat(result.attemptEvidence().attemptCount()).isEqualTo(1);
        assertThat(client.verifyCalled).isFalse();
    }

    @Test
    void suppliesConfiguredDefaultTimeoutWhenRequestOmitsTimeout() throws Exception {
        StubClient client = new StubClient(
                new FailureEvidenceResponse(200, JSON.readTree("""
                        {"fingerprint":{"exceptionType":"ExampleFailure","rootCauseType":"ExampleFailure",
                        "nearestApplicationFrame":{"className":"example.Order","methodName":"submit",
                        "lineNumber":42},"complete":true}}
                        """)), null);

        FailureAnalysisResult result = analyzeAction(client).execute(new AnalyzeTraceRequest(
                "trace", "http://sidecar.example", null, null, null));

        assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.ANALYZED);
        assertThat(client.analyzeTimeout).isEqualTo(POLICY.defaultTimeout());
    }

    @Test
    void normalizesRuntimeClientFailuresWithoutLeakingTechnicalDetails() {
        for (FailureEvidenceFailureKind kind : FailureEvidenceFailureKind.values()) {
            try {
                StubClient client = new StubClient(null, null);
                client.analyzeFailure = new FailureEvidenceClientException(kind, "Bearer internal-secret");

                FailureAnalysisResult result = analyzeAction(client).execute(new AnalyzeTraceRequest(
                        "trace", "http://sidecar.example", null, null, null));

                assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.BLOCKED_SIDECAR_UNAVAILABLE);
                assertThat(result.reasonCode()).isEqualTo("sidecar_failure_analysis_unavailable");
                assertThat(result.toString()).doesNotContain("internal-secret");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void preservesEverySupportedTerminalOutcomeAndCleanupStatusWithoutCallingSidecar() {
        List<String> outcomes = List.of(
                "BLOCKED_AMBIGUOUS_JVM", "BLOCKED_MISSING_AUTH", "BLOCKED_MISSING_TRIGGER",
                "BLOCKED_USER_ACTION_REQUIRED", "BLOCKED_UNSAFE_OPERATION", "ENVIRONMENT_MISMATCH",
                "INCONCLUSIVE", "CANCELLED");
        List<String> cleanupStatuses = List.of(
                "cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned");
        for (String outcome : outcomes) {
            for (String cleanupStatus : cleanupStatuses) {
                StubClient client = new StubClient(null, null);
                FailureAnalysisResult result = verifyAction(client).execute(new VerifyReproductionRequest(
                        null, null, null, null, null, null, null,
                        new FailureTerminalState(outcome, "terminal_reason", cleanupStatus, 2)));

                assertThat(result.outcome().value()).isEqualTo(outcome);
                assertThat(result.cleanupStatus().value()).isEqualTo(cleanupStatus);
                assertThat(result.attemptEvidence().attemptCount()).isEqualTo(2);
                assertThat(client.verifyCalled).isFalse();
            }
        }
    }

    @Test
    void normalizesInterruptedClientFailureAndRestoresInterruptFlag() {
        try {
            StubClient client = new StubClient(null, null);
            client.analyzeFailure = new FailureEvidenceClientException(
                    FailureEvidenceFailureKind.INTERRUPTED, "transport interrupted");

            FailureAnalysisResult result = analyzeAction(client).execute(new AnalyzeTraceRequest(
                    "trace", "http://sidecar.example", null, null, null));

            assertThat(result.outcome()).isEqualTo(FailureAnalysisOutcome.BLOCKED_SIDECAR_UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsMalformedEvidenceWithoutClaimingDiagnosis() throws Exception {
        StubClient analyzeClient = new StubClient(
                new FailureEvidenceResponse(200, JSON.readTree("{}")), null);
        FailureAnalysisResult analyzed = analyzeAction(analyzeClient).execute(new AnalyzeTraceRequest(
                "trace", "http://sidecar.example", null, null, null));

        StubClient verifyClient = new StubClient(null, new FailureEvidenceResponse(200, JSON.readTree("{}")));
        FailureAnalysisResult verified = verifyAction(verifyClient).execute(runtimeRequest());

        assertThat(analyzed.reasonCode()).isEqualTo("failure_fingerprint_incomplete");
        assertThat(analyzed.diagnosisClaimed()).isFalse();
        assertThat(verified.reasonCode()).isEqualTo("failure_verification_invalid");
        assertThat(verified.diagnosisClaimed()).isFalse();
    }

    @Test
    void boundsAndSanitizesUntrustedEvidence() throws Exception {
        var payload = JSON.createObjectNode();
        var fingerprint = payload.putObject("fingerprint");
        fingerprint.put("exceptionType", "ExampleFailure");
        fingerprint.put("rootCauseType", "ExampleFailure");
        fingerprint.put("normalizedMessage", "Bearer evidence-secret");
        fingerprint.put("complete", true);
        var candidates = payload.putArray("investigationCandidates");
        for (int index = 0; index < 10; index++) {
            var frame = candidates.addObject();
            frame.put("className", "example.Order");
            frame.put("methodName", "submit");
            frame.put("sourceFile", "C:\\Users\\alice\\secret\\Order.java");
            frame.put("codeSource", "/opt/alice/secret/order.jar");
            var paths = frame.putArray("codeSourceCandidates");
            for (int path = 0; path < 10; path++) {
                paths.add("/home/alice/secret/order-" + path + ".jar");
            }
        }
        var sections = payload.putArray("exceptionSections");
        for (int index = 0; index < 10; index++) {
            sections.addObject().putNull("exceptionType");
        }

        FailureAnalysisEvidence evidence = new FailureEvidenceResponseMapper(POLICY).analyze(payload);

        assertThat(evidence.fingerprint().normalizedMessage()).isEqualTo("<redacted>");
        assertThat(evidence.investigationCandidates()).hasSize(8);
        assertThat(evidence.investigationCandidates().get(0).sourceFile()).isEqualTo("<path>/Order.java");
        assertThat(evidence.investigationCandidates().get(0).codeSource()).isEqualTo("<path>/order.jar");
        assertThat(evidence.investigationCandidates().get(0).codeSourceCandidates()).hasSize(8);
        assertThat(evidence.investigationCandidates().get(0).codeSourceCandidates().get(0))
                .isEqualTo("<path>/order-0.jar");
        assertThat(evidence.exceptionSections()).hasSize(8);
        assertThat(evidence.exceptionSections().get(0).exceptionType()).isNull();
    }

    private AnalyzeTraceAction analyzeAction(StubClient client) {
        return new AnalyzeTraceAction(client, new FailureEvidenceResponseMapper(POLICY), POLICY);
    }

    private VerifyReproductionAction verifyAction(StubClient client) {
        return new VerifyReproductionAction(client, new FailureEvidenceResponseMapper(POLICY), POLICY);
    }

    private static FailureAnalysisActionHandler handler(FailureAnalysisAction action) {
        return new FailureAnalysisActionHandler() {
            @Override
            public FailureAnalysisAction action() {
                return action;
            }

            @Override
            public FailureAnalysisResult execute(
                    com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request
                            .FailureAnalysisRequest request) {
                return FailureAnalysisResult.invalidRequest();
            }
        };
    }

    private FailureInvestigationContext investigation() {
        return new FailureInvestigationContext("guided", 2, 60_000);
    }

    private VerifyReproductionRequest runtimeRequest() {
        return new VerifyReproductionRequest(
                "capture-1", FailureFingerprint.expected("ExampleFailure", "ExampleFailure", "example.Order#submit"),
                new FailureLineHitEvidence("example.Order#submit:42", 1),
                "http://sidecar.example", null, null, null, null);
    }

    private static final class StubClient implements FailureEvidenceClient {

        private final FailureEvidenceResponse analyze;
        private final FailureEvidenceResponse verify;
        private FailureEvidenceClientException analyzeFailure;
        private Duration analyzeTimeout;
        private boolean verifyCalled;

        private StubClient(FailureEvidenceResponse analyze, FailureEvidenceResponse verify) {
            this.analyze = analyze;
            this.verify = verify;
        }

        @Override
        public FailureEvidenceResponse analyze(
                com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint
                        .FailureAnalyzeEvidenceRequest request) {
            analyzeTimeout = request.timeout();
            if (analyzeFailure != null) {
                throw analyzeFailure;
            }
            return analyze;
        }

        @Override
        public FailureEvidenceResponse verify(
                com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint
                        .FailureVerifyEvidenceRequest request) {
            verifyCalled = true;
            return verify;
        }
    }
}
