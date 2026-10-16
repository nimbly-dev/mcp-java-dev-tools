package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

/** Immutable internal Security execution evidence. */
public record SecurityGeneratedRequest(URI uri, JsonNode request, boolean removeCredential) {
}
