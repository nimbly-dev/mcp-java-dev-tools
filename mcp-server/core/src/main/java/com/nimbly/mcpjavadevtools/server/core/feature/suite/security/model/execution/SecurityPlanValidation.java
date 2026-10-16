package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution;

import java.net.URI;

/** Validated plan routing values or a deterministic preflight failure. */
public record SecurityPlanValidation(String reasonCode, String nextAction, String mode, URI baseUrl) {
}
