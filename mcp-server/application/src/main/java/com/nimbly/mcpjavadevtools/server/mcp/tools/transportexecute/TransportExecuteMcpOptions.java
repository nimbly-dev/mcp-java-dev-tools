package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import org.jspecify.annotations.Nullable;

/** Optional public transport policy input; omitted wrappedOnly defaults to true. */
public record TransportExecuteMcpOptions(@Nullable Boolean wrappedOnly) {
}
