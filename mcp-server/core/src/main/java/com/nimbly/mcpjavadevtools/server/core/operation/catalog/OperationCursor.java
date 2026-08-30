package com.nimbly.mcpjavadevtools.server.core.operation.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;

/** Encodes and validates opaque catalog cursors without exposing operation IDs. */
final class OperationCursor {

    private OperationCursor() {
    }

    static String encode(int offset, String fingerprint) {
        if (offset < 0 || fingerprint == null || fingerprint.isBlank()) {
            throw invalid();
        }
        String value = "v1|" + offset + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static int decode(String cursor, String fingerprint) {
        try {
            if (cursor == null || cursor.length() > 512) {
                throw invalid();
            }
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            int offset = Integer.parseInt(parts[1]);
            if (parts.length != 3 || !"v1".equals(parts[0]) || !fingerprint.equals(parts[2]) || offset < 0) {
                throw invalid();
            }
            return offset;
        } catch (RuntimeException exception) {
            throw invalid(exception);
        }
    }

    static OperationDirectoryException invalid() {
        return new OperationDirectoryException(
                OperationExecutionStatus.INVALID_INPUT,
                "catalog_cursor_invalid",
                "The catalog cursor is invalid.");
    }

    static OperationDirectoryException invalid(RuntimeException cause) {
        OperationDirectoryException failure = invalid();
        failure.initCause(cause);
        return failure;
    }
}
