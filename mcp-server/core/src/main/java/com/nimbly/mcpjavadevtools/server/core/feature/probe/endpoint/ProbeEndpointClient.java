package com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;

/**
 * Intentional collaborator boundary for bounded Probe endpoint communication.
 *
 * <p>Action stories own concrete use of this port and endpoint response
 * interpretation. The foundation provides no live Sidecar invocation.</p>
 */
public interface ProbeEndpointClient {

    /**
     * Exchanges one validated endpoint request.
     *
     * @param request validated bounded request
     * @return raw bounded endpoint response
     */
    ProbeEndpointResponse exchange(ProbeEndpointRequest request);
}
