package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;

/**
 * Closed status-request family for single and batch Strict Line Key operations.
 */
public sealed interface ProbeStatusRequest extends ProbeRequest
        permits ProbeSingleStatusRequest, ProbeBatchStatusRequest {
}
