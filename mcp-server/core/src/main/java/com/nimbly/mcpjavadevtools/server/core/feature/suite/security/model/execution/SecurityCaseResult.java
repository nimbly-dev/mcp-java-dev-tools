package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution;

import java.util.List;
import java.util.Map;

/** Immutable internal Security execution evidence. */
public record SecurityCaseResult(Map<String, Object> coverage, List<Map<String, Object>> findings) {
}
