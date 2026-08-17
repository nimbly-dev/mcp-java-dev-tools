package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.DefaultFailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.AnalyzeTraceAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.VerifyReproductionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.VerifyReproductionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerifyEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

/** Verifies Failure Analysis output fields against the checked-in TypeScript contract matrix. */
class FailureAnalysisMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FailureAnalysisPolicy POLICY = new FailureAnalysisPolicy(
            Duration.ofSeconds(15), 200_000, 65_536, 256, 8, 8);
    private static final FailureFingerprint EXPECTED = FailureFingerprint.expected(
            "java.lang.IllegalStateException", "java.lang.IllegalArgumentException", "example.Order#submit");
    private static final FailureLineHitEvidence LINE_HIT = new FailureLineHitEvidence("example.Order#submit:42", 2);

    @Test
    void preservesTypeScriptOutcomeAndRedactionContractThroughCoreActions() throws IOException {
        FailureAnalysisMcpResponseMapper responseMapper = new FailureAnalysisMcpResponseMapper();
        try (InputStream stream = getClass().getResourceAsStream(
                "/failure-analysis/failure-analysis-typescript-java-parity.json")) {
            assertThat(stream).isNotNull();
            JsonNode fixture = JSON.readTree(stream);
            for (JsonNode testCase : fixture.path("cases")) {
                String kind = testCase.path("kind").asText();
                FailureAnalysisRequest request = request(kind);
                FailureAnalysisResult result = feature(client(kind)).execute(request);
                assertFixture(testCase, JSON.valueToTree(responseMapper.map(request, result)));
            }
            FailureAnalysisRequest request = request("analyzed");
            FailureAnalysisResult result = feature(client("analyzed")).execute(request);
            String output = JSON.writeValueAsString(responseMapper.map(request, result));
            for (JsonNode forbidden : fixture.path("redaction").path("forbiddenValues")) {
                assertThat(output).doesNotContain(forbidden.asText());
            }
        }
    }

    private static void assertFixture(JsonNode testCase, JsonNode actual) {
        Iterator<Entry<String, JsonNode>> assertions = testCase.path("assertions").fields();
        while (assertions.hasNext()) {
            Entry<String, JsonNode> assertion = assertions.next();
            assertThat(actual.at('/' + assertion.getKey().replace('.', '/')).toString())
                    .as("%s %s", testCase.path("name").asText(), assertion.getKey())
                    .isEqualTo(assertion.getValue().toString());
        }
    }

    private static FailureAnalysisFeature feature(StubClient client) {
        FailureEvidenceResponseMapper mapper = new FailureEvidenceResponseMapper(POLICY);
        List<FailureAnalysisActionHandler> handlers = List.of(
                new AnalyzeTraceAction(client, mapper, POLICY),
                new VerifyReproductionAction(client, mapper, POLICY));
        return new DefaultFailureAnalysisFeature(handlers);
    }

    private static FailureAnalysisRequest request(String kind) {
        return switch (kind) {
            case "analyzed" -> new AnalyzeTraceRequest(
                    "java.lang.IllegalStateException: Bearer parity-secret", "http://sidecar.example",
                    "Bearer parity-secret", null, null);
            case "reproduced", "not_reproduced" -> new VerifyReproductionRequest(
                    "capture-1", EXPECTED, LINE_HIT, "http://sidecar.example", "Bearer parity-secret",
                    null, null, null);
            case "terminal" -> new VerifyReproductionRequest(
                    null, null, null, null, null, null, null,
                    new FailureTerminalState("BLOCKED_MISSING_AUTH", "missing_auth", "cleanup_confirmed", 1));
            default -> throw new IllegalArgumentException("unknown fixture case: " + kind);
        };
    }

    private static StubClient client(String kind) throws IOException {
        return switch (kind) {
            case "analyzed" -> new StubClient(response("""
                    {"fingerprint":{"exceptionType":"java.lang.IllegalStateException",
                    "rootCauseType":"java.lang.IllegalArgumentException",
                    "nearestApplicationFrame":{"className":"example.Order","methodName":"submit",
                    "lineNumber":42,"ownership":"application"},
                    "normalizedMessage":"Bearer parity-secret","complete":true},
                    "investigationCandidates":[],"exceptionSections":[],"reasons":[]}
                    """), null);
            case "reproduced" -> new StubClient(null, response("""
                    {"outcome":"matched","observedFingerprint":{"exceptionType":"java.lang.IllegalStateException",
                    "rootCauseType":"java.lang.IllegalArgumentException",
                    "nearestApplicationFrame":{"className":"example.Order","methodName":"submit",
                    "lineNumber":42},"complete":true},"reasons":[]}
                    """));
            case "not_reproduced" -> new StubClient(null, response("""
                    {"outcome":"different_exception","observedFingerprint":{"exceptionType":"java.lang.RuntimeException",
                    "rootCauseType":"java.lang.RuntimeException",
                    "nearestApplicationFrame":{"className":"example.Order","methodName":"submit",
                    "lineNumber":42},"complete":true},"reasons":[]}
                    """));
            case "terminal" -> new StubClient(null, null);
            default -> throw new IllegalArgumentException("unknown fixture case: " + kind);
        };
    }

    private static FailureEvidenceResponse response(String payload) throws IOException {
        return new FailureEvidenceResponse(200, JSON.readTree(payload));
    }

    private static final class StubClient implements FailureEvidenceClient {

        private final FailureEvidenceResponse analyze;
        private final FailureEvidenceResponse verify;

        private StubClient(FailureEvidenceResponse analyze, FailureEvidenceResponse verify) {
            this.analyze = analyze;
            this.verify = verify;
        }

        @Override
        public FailureEvidenceResponse analyze(FailureAnalyzeEvidenceRequest request) {
            return analyze;
        }

        @Override
        public FailureEvidenceResponse verify(FailureVerifyEvidenceRequest request) {
            return verify;
        }
    }
}
