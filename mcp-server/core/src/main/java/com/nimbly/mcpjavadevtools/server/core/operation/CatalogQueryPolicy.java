package com.nimbly.mcpjavadevtools.server.core.operation;

import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;

/** Core-enforced catalog page-size policy supplied by the Application boundary. */
public record CatalogQueryPolicy(int defaultLimit, int maximumLimit) {

    /** Creates a validated policy bounded by the Core hard maximum. */
    public CatalogQueryPolicy {
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("catalog default limit must be positive");
        }
        if (maximumLimit < defaultLimit || maximumLimit > CatalogQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "catalog maximum limit must be between the default limit and " + CatalogQuery.MAX_LIMIT);
        }
    }

    /** Creates the standard public policy. */
    public static CatalogQueryPolicy defaults() {
        return new CatalogQueryPolicy(CatalogQuery.DEFAULT_LIMIT, CatalogQuery.MAX_LIMIT);
    }

    /** Creates a normalized query while enforcing the configured deployment limit. */
    public CatalogQuery query(
            String search,
            String api,
            String classification,
            Integer requestedLimit,
            String cursor) {
        int limit = requestedLimit == null ? defaultLimit : requestedLimit;
        if (limit < 1 || limit > maximumLimit) {
            throw new OperationDirectoryException(
                    OperationExecutionStatus.INVALID_INPUT,
                    "catalog_limit_invalid",
                    "The catalog limit must be between 1 and " + maximumLimit + ".");
        }
        return new CatalogQuery(search, api, classification, limit, cursor);
    }
}
