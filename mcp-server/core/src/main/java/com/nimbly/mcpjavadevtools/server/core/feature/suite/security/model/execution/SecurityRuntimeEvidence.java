package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution;

import java.util.List;
import java.util.Map;

/** Immutable internal Security execution evidence. */
public record SecurityRuntimeEvidence(boolean blocked, String reasonCode, boolean requiredHit, boolean forbiddenHit,
        List<Map<String, Object>> observations) {
}
