/**
 * Owns Spring-independent transport execution policy, provider dispatch, and
 * deterministic HTTP outcomes for the {@code transport_execute} MCP Tool.
 *
 * <p>This package must not depend on Spring AI, Spring Boot, or MCP transport
 * types. The active Probe Registry supplies wrapper policy through an explicit
 * Core collaborator contract.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;
