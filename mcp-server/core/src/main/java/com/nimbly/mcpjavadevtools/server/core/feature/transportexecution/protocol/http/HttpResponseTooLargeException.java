package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import java.io.IOException;

/** Signals that an HTTP response exceeded the hard Core body limit. */
final class HttpResponseTooLargeException extends IOException {
}
