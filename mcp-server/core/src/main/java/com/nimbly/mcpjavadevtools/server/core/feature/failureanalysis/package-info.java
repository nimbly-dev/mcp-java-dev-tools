/**
 * Owns Spring-independent Failure Analysis models, policies, clients, and
 * action handlers for Java traces and bounded runtime reproduction evidence.
 *
 * <p>Core operation registration validates the complete handler set and binds
 * each handler directly. This package may depend on the JDK and
 * Core-owned collaborators only; it must not depend on Spring AI, Spring Boot,
 * MCP transport types, or Sidecar implementation internals.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis;
