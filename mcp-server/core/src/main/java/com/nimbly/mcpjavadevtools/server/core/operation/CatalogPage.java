package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.List;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogEntry;

/** Immutable, bounded aggregate catalog page. */
public record CatalogPage(
        List<OperationCatalogEntry> entries,
        String nextCursor,
        int totalMatches) {

    /** Validates and copies one deterministic page. */
    public CatalogPage {
        Objects.requireNonNull(entries, "catalog entries must not be null");
        if (totalMatches < entries.size()) {
            throw new IllegalArgumentException("catalog total cannot be smaller than page size");
        }
        entries = List.copyOf(entries);
    }

    /** @return compatibility alias for callers that name rows results */
    public List<OperationCatalogEntry> results() {
        return entries;
    }

    /** @return whether another bounded page is available */
    public boolean hasMore() {
        return nextCursor != null;
    }
}
