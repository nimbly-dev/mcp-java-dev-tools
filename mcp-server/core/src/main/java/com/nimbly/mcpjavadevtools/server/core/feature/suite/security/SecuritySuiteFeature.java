package com.nimbly.mcpjavadevtools.server.core.feature.suite.security;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;

/** Spring-independent public entry point for Security Suite behavior. */
public interface SecuritySuiteFeature {

    /** Executes one Security Suite action. */
    SecuritySuiteResult execute(SecuritySuiteRequest request);
}
