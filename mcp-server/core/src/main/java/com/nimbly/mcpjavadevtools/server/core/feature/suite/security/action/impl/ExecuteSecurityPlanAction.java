package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.SecuritySuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import java.util.Objects;

/** Executes one validated Security plan. */
public final class ExecuteSecurityPlanAction implements SecuritySuiteActionHandler {

    private final SecurityPlanExecutor executor;

    /** Creates the action with its plan executor. */
    public ExecuteSecurityPlanAction(SecurityPlanExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public SecuritySuiteAction action() {
        return SecuritySuiteAction.EXECUTE_PLAN;
    }

    @Override
    public SecuritySuiteResult execute(SecuritySuiteRequest request) {
        return executor.execute(request.input());
    }
}
