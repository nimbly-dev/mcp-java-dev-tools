package com.nimbly.mcpjavadevtools.requestmapping.core;

import com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Binary-compatible Request Mapper index retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
public final class TypeIndex {
    private final Map<String, List<TypeDescriptor>> bySimpleName;
    private final Map<String, List<TypeDescriptor>> byFqcn;
    private final int typeCount;

    public TypeIndex(Map<String, List<TypeDescriptor>> bySimpleName,
                     Map<String, List<TypeDescriptor>> byFqcn,
                     int typeCount) {
        this.bySimpleName = bySimpleName;
        this.byFqcn = byFqcn;
        this.typeCount = typeCount;
    }

    public TypeIndex(List<TypeDescriptor> descriptors) {
        this.bySimpleName = new HashMap<>();
        this.byFqcn = new HashMap<>();
        for (TypeDescriptor descriptor : descriptors) {
            bySimpleName.computeIfAbsent(descriptor.getSimpleName(), ignored -> new ArrayList<>()).add(descriptor);
            byFqcn.computeIfAbsent(descriptor.getFqcn(), ignored -> new ArrayList<>()).add(descriptor);
        }
        this.typeCount = descriptors.size();
    }

    public int getTypeCount() { return typeCount; }

    public List<TypeDescriptor> lookupTypes(String classHint) {
        if (classHint == null || classHint.isBlank()) return List.of();
        return classHint.contains(".")
                ? byFqcn.getOrDefault(classHint, List.of())
                : bySimpleName.getOrDefault(classHint, List.of());
    }

    public TypeDescriptor resolveTypeReference(TypeDescriptor owner, String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.contains(".")) return unique(byFqcn.get(reference));
        for (String imported : owner.getImports()) {
            if (imported.endsWith("." + reference)) {
                TypeDescriptor match = unique(byFqcn.get(imported));
                if (match != null) return match;
            }
            if (imported.endsWith(".*")) {
                TypeDescriptor match = unique(
                        byFqcn.get(imported.substring(0, imported.length() - 2) + "." + reference));
                if (match != null) return match;
            }
        }
        if (!owner.getPackageName().isBlank()) {
            TypeDescriptor match = unique(
                    byFqcn.get(owner.getPackageName() + "." + reference));
            if (match != null) return match;
        }
        return unique(bySimpleName.get(reference));
    }

    private static TypeDescriptor unique(List<TypeDescriptor> descriptors) {
        return descriptors == null || descriptors.size() != 1 ? null : descriptors.get(0);
    }
}
