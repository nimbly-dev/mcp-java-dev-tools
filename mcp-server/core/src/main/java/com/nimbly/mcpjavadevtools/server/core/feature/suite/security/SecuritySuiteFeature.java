package com.nimbly.mcpjavadevtools.server.core.feature.suite.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;

/** Contract consumed by direct CDE execution and cross-suite orchestration. */
public interface SecuritySuiteFeature {

    /** Executes a resolved Security plan with fail-closed coverage semantics. */
    SecuritySuiteResult executePlan(JsonNode input);
}
