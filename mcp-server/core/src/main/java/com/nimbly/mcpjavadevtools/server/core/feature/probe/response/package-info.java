/**
 * Safe, bounded Probe response compaction and sensitive-value redaction.
 *
 * <p>Action stories choose the response data they own; this package prevents
 * raw Sidecar payloads and credential headers from crossing that boundary.</p>
 */
package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;
