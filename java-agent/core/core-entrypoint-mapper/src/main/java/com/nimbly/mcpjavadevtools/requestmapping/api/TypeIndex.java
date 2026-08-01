package com.nimbly.mcpjavadevtools.requestmapping.api;

import com.nimbly.mcpjavadevtools.requestmapping.core.TypeReferenceResolver;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

public final class TypeIndex {
    private final Map<String, List<TypeDescriptor>> bySimpleName;
    private final Map<String, List<TypeDescriptor>> byFqcn;
    private final int typeCount;

    public TypeIndex(
            Map<String, List<TypeDescriptor>> bySimpleName,
            Map<String, List<TypeDescriptor>> byFqcn,
            int typeCount
    ) {
        this.bySimpleName = bySimpleName;
        this.byFqcn = byFqcn;
        this.typeCount = typeCount;
    }

    public int getTypeCount() {
        return typeCount;
    }

    public List<TypeDescriptor> lookupTypes(String classHint) {
        if (classHint == null || classHint.isBlank()) {
            return List.of();
        }
        if (classHint.contains(".")) {
            return byFqcn.getOrDefault(classHint, List.of());
        }
        return bySimpleName.getOrDefault(classHint, List.of());
    }

    public TypeDescriptor resolveTypeReference(TypeDescriptor owner, String reference) {
        return TypeReferenceResolver.resolve(owner, reference, bySimpleName, byFqcn);
    }

    /** Returns the indexed descriptors for compatibility-boundary conversion. */
    public List<TypeDescriptor> descriptors() {
        return List.copyOf(new LinkedHashSet<>(byFqcn.values().stream()
                .flatMap(List::stream)
                .toList()));
    }
}



