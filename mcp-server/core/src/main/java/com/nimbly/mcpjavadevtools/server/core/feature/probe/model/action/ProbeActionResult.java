package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action;

/**
 * Common marker for typed results returned by completed Probe actions.
 *
 * <p>Each action owns its concrete result in {@code model.action.<action>}.
 * This shared boundary avoids a generic response payload map.</p>
 */
public interface ProbeActionResult {
}
