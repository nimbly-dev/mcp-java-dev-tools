package com.nimbly.mcpjavadevtools.server.core.feature.suite.security;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.SecuritySuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import java.util.List;
import java.util.Map;

/** Default production Security Suite Feature implementation. */
public final class DefaultSecuritySuiteFeature implements SecuritySuiteFeature {

    private final EnumActionDispatcher<SecuritySuiteAction, SecuritySuiteRequest, SecuritySuiteResult> dispatcher;

    /** Creates the Feature with a complete action-handler set. */
    public DefaultSecuritySuiteFeature(List<? extends SecuritySuiteActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(SecuritySuiteAction.class, handlers);
    }

    @Override
    public SecuritySuiteResult execute(SecuritySuiteRequest request) {
        if (request == null || request.action() == null) {
            return SecuritySuiteResult.blocked(
                    "security_suite_request_invalid",
                    "a Security Suite action is required",
                    Map.of("failedStep", "input_validation"));
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
