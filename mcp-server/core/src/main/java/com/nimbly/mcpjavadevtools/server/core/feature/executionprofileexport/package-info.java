/**
 * Owns Spring-independent Execution Profile replay export behavior.
 *
 * <p>The public entry point is {@code ExecutionProfileExportFeature}. Artifact
 * persistence is delegated through the approved Artifact gateway. This package
 * does not own MCP transport, Spring configuration, or Sidecar implementation.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;
