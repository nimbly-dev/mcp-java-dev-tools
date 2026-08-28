/**
 * Owns Spring-independent execution-profile orchestration and its bounded
 * Suite routing, resume, lease, and run-state collaborators.
 *
 * <p>Its public entry point is {@code ExecutionOrchestrationFeature}. It does
 * not own MCP transport, Spring composition, Sidecar implementation, or
 * Suite-private behavior. Application adapters and sibling Core Features may
 * depend only on the intentional public Feature surface.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration;
