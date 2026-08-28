package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;

/** Typed Security Suite action-handler contract. */
public interface SecuritySuiteActionHandler
        extends ActionHandler<SecuritySuiteAction, SecuritySuiteRequest, SecuritySuiteResult> {
}
