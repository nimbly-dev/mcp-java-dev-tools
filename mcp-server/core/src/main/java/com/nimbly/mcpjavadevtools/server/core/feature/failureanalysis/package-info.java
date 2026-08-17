/**
 * Owns Spring-independent Failure Analysis behavior for pasted Java traces and
 * bounded runtime reproduction evidence.
 *
 * <p>The public entry point is {@link FailureAnalysisFeature}. This package may
 * depend on the JDK and Core-owned collaborators only; it must not depend on
 * Spring AI, Spring Boot, MCP transport types, or Sidecar implementation
 * internals.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;
