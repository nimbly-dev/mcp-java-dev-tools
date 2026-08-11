package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;

/**
 * Closed reset-request family for single, batch, and class selectors.
 */
public sealed interface ProbeResetRequest extends ProbeRequest
        permits ProbeSingleResetRequest, ProbeBatchResetRequest, ProbeClassResetRequest {
}
