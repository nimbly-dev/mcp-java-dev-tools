/**
 * Owns Spring-independent Performance Suite execution and bounded JMeter
 * workload behavior.
 *
 * <p>Its public entry point is {@code PerformanceSuiteFeature}. It does not
 * own MCP transport, Spring composition, Artifact workspace discovery, or
 * Sidecar implementation. Orchestration depends only on the public Feature.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance;
