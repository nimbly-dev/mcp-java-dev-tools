package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Locale;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;

/** Bounded aggregate catalog query with stable filtering and pagination state. */
public record CatalogQuery(
        String search,
        String api,
        String classification,
        int limit,
        String cursor) {

    /** Default page size used when callers omit a limit. */
    public static final int DEFAULT_LIMIT = 10;
    /** Hard upper bound for one catalog page. */
    public static final int MAX_LIMIT = 50;

    /** Normalizes query text and enforces the public catalog bounds. */
    public CatalogQuery {
        search = normalize(search);
        api = normalize(api);
        classification = normalize(classification);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OperationDirectoryException(
                    OperationExecutionStatus.INVALID_INPUT,
                    "catalog_limit_invalid",
                    "The catalog limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (search != null && search.length() > 256
                || api != null && api.length() > 128
                || classification != null && classification.length() > 128) {
            throw new OperationDirectoryException(
                    OperationExecutionStatus.INVALID_INPUT,
                    "catalog_filter_invalid",
                    "The catalog filter exceeds its supported bound.");
        }
        cursor = cursor == null || cursor.isBlank() ? null : cursor;
    }

    /** Creates the default first page. */
    public CatalogQuery() {
        this(null, null, null, DEFAULT_LIMIT, null);
    }

    /** Creates a search query using the default page size. */
    public CatalogQuery(String search) {
        this(search, null, null, DEFAULT_LIMIT, null);
    }

    /** Creates an exact API/classification query using the default page size. */
    public CatalogQuery(String search, String api, String classification) {
        this(search, api, classification, DEFAULT_LIMIT, null);
    }

    public String fingerprint() {
        return Integer.toHexString(Objects.hash(search, api, classification, limit));
    }

    static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
