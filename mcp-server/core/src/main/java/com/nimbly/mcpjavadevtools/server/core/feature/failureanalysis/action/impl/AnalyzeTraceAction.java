package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalysisEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;

/** Real analyze_trace action backed by the existing Sidecar debug endpoint. */
public final class AnalyzeTraceAction implements FailureAnalysisActionHandler {

    private final FailureEvidenceClient client;
    private final FailureEvidenceResponseMapper responseMapper;
    private final FailureAnalysisPolicy policy;

    public AnalyzeTraceAction(
            FailureEvidenceClient client,
            FailureEvidenceResponseMapper responseMapper,
            FailureAnalysisPolicy policy) {
        this.client = client;
        this.responseMapper = responseMapper;
        this.policy = policy;
    }

    @Override
    public FailureAnalysisAction action() {
        return FailureAnalysisAction.ANALYZE_TRACE;
    }

    @Override
    public FailureAnalysisResult execute(FailureAnalysisRequest input) {
        if (!(input instanceof AnalyzeTraceRequest request)
                || request.trace().length() > policy.maximumTraceCharacters()) {
            return FailureAnalysisResult.invalidRequest();
        }
        try {
            FailureEvidenceResponse response = client.analyze(new FailureAnalyzeEvidenceRequest(
                    request.sidecarBaseUrl(), request.trace(), request.sidecarAuthorization(),
                    policy.timeoutOrDefault(request.timeout())));
            return result(response, request);
        } catch (FailureEvidenceClientException exception) {
            return normalize(exception);
        } catch (RuntimeException exception) {
            return FailureAnalysisResult.blockedAnalyze(null);
        }
    }

    private FailureAnalysisResult result(
            FailureEvidenceResponse response, AnalyzeTraceRequest request) {
        if (response.status() < 200 || response.status() >= 300 || response.payload() == null) {
            return FailureAnalysisResult.blockedAnalyze(response.status());
        }
        FailureAnalysisEvidence evidence = responseMapper.analyze(response.payload());
        if (evidence.fingerprint() == null || !evidence.fingerprint().complete()) {
            return FailureAnalysisResult.incompleteTrace(
                    evidence.fingerprint(), evidence.investigationCandidates(), evidence.dependencyBoundary(),
                    evidence.exceptionSections(), evidence.reasons(), request.investigation());
        }
        return FailureAnalysisResult.analyzed(
                evidence.fingerprint(), evidence.investigationCandidates(), evidence.dependencyBoundary(),
                evidence.exceptionSections(), evidence.reasons(), request.investigation());
    }

    private FailureAnalysisResult normalize(FailureEvidenceClientException exception) {
        if (exception.failureKind() == com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint
                .FailureEvidenceFailureKind.INTERRUPTED) {
            Thread.currentThread().interrupt();
        }
        return FailureAnalysisResult.blockedAnalyze(null);
    }
}
