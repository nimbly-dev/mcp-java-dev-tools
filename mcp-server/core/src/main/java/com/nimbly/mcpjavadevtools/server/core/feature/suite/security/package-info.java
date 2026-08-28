/**
 * Owns Spring-independent Security Suite execution through bounded Black-box
 * and Sidecar-assisted Core contracts.
 *
 * <p>Its public entry point is {@code SecuritySuiteFeature}. It does not own
 * MCP transport, Spring composition, Artifact workspace discovery, or the
 * Sidecar Agent implementation. Orchestration depends only on this Feature.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.suite.security;
