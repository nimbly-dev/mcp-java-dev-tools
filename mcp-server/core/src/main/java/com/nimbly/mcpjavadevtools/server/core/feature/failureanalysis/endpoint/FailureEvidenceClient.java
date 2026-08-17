package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureAnalyzeEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerifyEvidenceRequest;

/** Purpose-owned boundary for existing Sidecar Failure Lens endpoints. */
public interface FailureEvidenceClient {

    /** @param request bounded trace analysis request @return bounded HTTP response */
    FailureEvidenceResponse analyze(FailureAnalyzeEvidenceRequest request);

    /** @param request bounded capture verification request @return bounded HTTP response */
    FailureEvidenceResponse verify(FailureVerifyEvidenceRequest request);
}
