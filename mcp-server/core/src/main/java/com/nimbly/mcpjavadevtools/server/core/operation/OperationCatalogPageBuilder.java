package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.ArrayList;
import java.util.List;

/** Builds deterministic bounded catalog pages from one immutable manifest. */
public final class OperationCatalogPageBuilder {

    private OperationCatalogPageBuilder() {
    }

    public static CatalogPage build(OperationManifest manifest, CatalogQuery query) {
        CatalogQuery checkedQuery = query == null ? new CatalogQuery() : query;
        List<OperationCatalogEntry> matches = new ArrayList<>();
        for (OperationRegistration<?, ?> registration
                : manifest.registrations()) {
            OperationDescriptor descriptor = registration.descriptor();
            if (matches(descriptor, checkedQuery)) {
                matches.add(OperationCatalogEntry.from(descriptor));
            }
        }
        int offset = checkedQuery.cursor() == null ? 0
                : OperationCursor.decode(checkedQuery.cursor(), checkedQuery.fingerprint());
        if (offset > matches.size()) {
            throw new OperationDirectoryException(
                    OperationExecutionStatus.INVALID_INPUT,
                    "catalog_cursor_invalid",
                    "The catalog cursor is outside the result set.");
        }
        int end = Math.min(offset + checkedQuery.limit(), matches.size());
        String nextCursor = end < matches.size()
                ? OperationCursor.encode(end, checkedQuery.fingerprint()) : null;
        return new CatalogPage(matches.subList(offset, end), nextCursor, matches.size());
    }

    static boolean matches(OperationDescriptor descriptor, CatalogQuery query) {
        if (query.api() != null && !query.api().equals(descriptor.api())) {
            return false;
        }
        if (query.classification() != null && !query.classification().equals(
                descriptor.classification().toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        if (query.search() == null) {
            return true;
        }
        String searchable = descriptor.operationId().value() + " " + descriptor.api() + " "
                + descriptor.operation() + " " + descriptor.classification() + " "
                + descriptor.summary() + " " + String.join(" ", descriptor.documentation().tags());
        return searchable.toLowerCase(java.util.Locale.ROOT).contains(query.search());
    }
}
